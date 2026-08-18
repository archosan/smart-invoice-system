package com.archosan.invoice.extraction.pipeline;

import com.archosan.invoice.extraction.llm.LlmExtraction;
import com.archosan.invoice.extraction.llm.LlmInvoiceClient;
import com.archosan.invoice.extraction.llm.LlmUnavailableException;
import com.archosan.invoice.extraction.llm.OllamaAvailability;
import com.archosan.invoice.extraction.runs.ExtractionRunRepository;
import com.archosan.invoice.extraction.text.DocumentIntegrityException;
import com.archosan.invoice.extraction.text.PdfTextExtractor;
import com.archosan.invoice.extraction.text.TextExtraction;
import com.archosan.invoice.extraction.validation.ConfidenceScore;
import com.archosan.invoice.extraction.validation.InvoiceValidator;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.RuleResult;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.consumer.InboxCompletion;
import com.archosan.invoice.messaging.consumer.LongRunningMessageHandler;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code ExtractInvoice} işlem hattı (DECISIONS.md §4.2; uzun iş, B-06): metin çıkarımı (B-17), sınırlı yeniden
 * denemeli LLM (B-18, B-19), alan eşleme; sonuç {@link InboxCompletion} ile inbox kaydıyla aynı kısa transaction'da
 * outbox'a yazılır.
 *
 * <ul>
 *   <li>Metin yok → {@code ExtractionFailed(NO_TEXT)}.</li>
 *   <li>Deneme bütçesi tükendi → {@code ExtractionFailed(LLM_RETRIES_EXHAUSTED)}, ack (US-05).</li>
 *   <li>Adres veya hash hatası kalıcıdır → DLQ.</li>
 *   <li>Ollama'ya ulaşılamıyor → dinleyici durdurulur, mesaj geri konur (A1); izin alınamaması da geçicidir.</li>
 * </ul>
 * Başarılı çıkarımda alanlar kurallarla doğrulanır (FR-E3) ve güven skoru hesaplanır (FR-E4, B-20); eşik kararı
 * document-service'tedir (ADR-13).
 */
@Component
public class ExtractInvoiceHandler implements LongRunningMessageHandler<ExtractInvoice> {

    private static final Logger log = LoggerFactory.getLogger(ExtractInvoiceHandler.class);

    private final PdfTextExtractor textExtractor;
    private final LlmExtraction llmExtraction;
    private final LlmInvoiceClient llm;
    private final OllamaAvailability ollama;
    private final InvoiceValidator validator;
    private final ConfidenceScore confidence;
    private final ExtractionRunRepository runs;
    private final OutboxWriter outbox;

    public ExtractInvoiceHandler(PdfTextExtractor textExtractor, LlmExtraction llmExtraction, LlmInvoiceClient llm,
            OllamaAvailability ollama, InvoiceValidator validator, ConfidenceScore confidence,
            ExtractionRunRepository runs, OutboxWriter outbox) {
        this.textExtractor = textExtractor;
        this.llmExtraction = llmExtraction;
        this.llm = llm;
        this.ollama = ollama;
        this.validator = validator;
        this.confidence = confidence;
        this.runs = runs;
        this.outbox = outbox;
    }

    @Override
    public void handle(MessageEnvelope envelope, ExtractInvoice command, InboxCompletion completion) {
        UUID documentId = command.documentId();
        TextExtraction text;
        try {
            text = textExtractor.extract(command.storageUri(), command.fileSha256());
        } catch (DocumentIntegrityException e) {
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }

        switch (text) {
            case TextExtraction.NoText noText -> {
                log.info("Metin yok, ExtractionFailed(NO_TEXT): {}", noText.detail());
                completion.complete(() -> outbox.add(documentId,
                        new ExtractionFailed(documentId, ExtractionFailed.Reason.NO_TEXT, noText.detail(), 0)));
            }
            case TextExtraction.Extracted extracted -> {
                LlmExtraction.Result result;
                try {
                    result = llmExtraction.extract(extracted.text());
                } catch (LlmUnavailableException e) {
                    ollama.markUnavailable(e.getMessage());
                    throw e;
                }
                switch (result) {
                    case LlmExtraction.Succeeded ok -> {
                        InvoiceFields fields = InvoiceMapper.map(ok.invoice());
                        List<RuleResult> rules = validator.validate(ok.invoice(), fields);
                        BigDecimal score = confidence.score(rules);
                        ExtractionCompleted event = new ExtractionCompleted(documentId, fields, score, rules,
                                llm.model(), llm.promptVersion());
                        completion.complete(() -> {
                            runs.insertAll(documentId, llm.model(), llm.promptVersion(), ok.history());
                            outbox.add(documentId, event);
                        });
                        log.info("Çıkarım tamamlandı: güven={}, kalan kurallar={}, deneme={}, model={}, prompt={}",
                                score, rules.stream().filter(r -> !r.passed()).map(RuleResult::rule).toList(),
                                ok.attempts(), llm.model(), llm.promptVersion());
                    }
                    case LlmExtraction.Exhausted exhausted -> {
                        log.warn("Deneme bütçesi tükendi, ExtractionFailed(LLM_RETRIES_EXHAUSTED): {}",
                                exhausted.lastError());
                        completion.complete(() -> {
                            runs.insertAll(documentId, llm.model(), llm.promptVersion(), exhausted.history());
                            outbox.add(documentId, new ExtractionFailed(documentId,
                                    ExtractionFailed.Reason.LLM_RETRIES_EXHAUSTED, exhausted.lastError(),
                                    exhausted.attempts()));
                        });
                    }
                }
            }
        }
    }
}
