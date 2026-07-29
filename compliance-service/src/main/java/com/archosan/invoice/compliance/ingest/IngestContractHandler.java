package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.compliance.ComplianceProperties;
import com.archosan.invoice.compliance.contract.ContractRepository;
import com.archosan.invoice.compliance.contract.ContractRepository.Contract;
import com.archosan.invoice.compliance.contract.ContractRepository.NewChunk;
import com.archosan.invoice.compliance.contract.ContractRepository.Status;
import com.archosan.invoice.compliance.ingest.ContractChunker.Chunk;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.consumer.InboxCompletion;
import com.archosan.invoice.messaging.consumer.LongRunningMessageHandler;
import com.archosan.invoice.messaging.message.IngestContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code compliance.ingest-contract.q}: indeksleme (B-45, FR-C1). Metin çıkarma, normalizasyon, chunking ve embedding
 * transaction dışında yapılır (uzun iş); sonuç tek transaction'da yazılır: önceki yarım denemenin chunk'ları silinir,
 * yenileri eklenir, sözleşme {@code INGESTING → READY} olur. Yarım indeksli sözleşme seçilmez (§4.3 adım 6).
 *
 * <ul>
 *   <li>Metin yoksa (taranmış PDF): {@code FAILED}, neden {@code NO_TEXT}; iş sonucudur, DLQ değil.</li>
 *   <li>Dosya yok ya da PDF okunamıyor: kalıcı hata, DLQ → {@code FAILED} ({@link IngestDeadLetterHandler}).</li>
 *   <li>Ollama'ya ulaşılamıyor: geçici, mesaj geri konur; teslim limitinden sonra DLQ → {@code FAILED}.</li>
 *   <li>Sözleşme {@code INGESTING} değilse (tekrar gelen komut): bir şey yapılmaz.</li>
 * </ul>
 */
@Component
class IngestContractHandler implements LongRunningMessageHandler<IngestContract> {

    private static final Logger log = LoggerFactory.getLogger(IngestContractHandler.class);

    private final ContractRepository contracts;
    private final ContractEmbedder embedder;
    private final ContractChunker chunker;
    private final int minTextChars;

    IngestContractHandler(ContractRepository contracts, ContractEmbedder embedder, ComplianceProperties properties) {
        this.contracts = contracts;
        this.embedder = embedder;
        this.chunker = new ContractChunker(properties.ingest());
        this.minTextChars = properties.ingest().minTextChars();
    }

    @Override
    public void handle(MessageEnvelope envelope, IngestContract command, InboxCompletion completion) {
        Contract contract = contracts.find(command.contractId()).orElseThrow(
                () -> new AmqpRejectAndDontRequeueException("Bilinmeyen sözleşme: " + command.contractId()));
        if (contract.status() != Status.INGESTING) {
            log.info("Sözleşme INGESTING değil ({}), komut atlandı", contract.status());
            completion.complete(() -> { });
            return;
        }

        List<ContractNormalizer.Line> lines = ContractNormalizer.normalize(read(command.storageUri()));
        int textChars = lines.stream().mapToInt(l -> l.text().length()).sum();
        if (textChars < minTextChars) {
            String reason = "NO_TEXT: metin katmanı yok ya da çok kısa (" + textChars + " karakter); OCR kapsam dışı";
            completion.complete(() -> contracts.transition(contract.id(), Status.INGESTING, Status.FAILED, reason));
            log.warn("Sözleşme indekslenemedi: {}", reason);
            return;
        }

        List<Chunk> chunks = chunker.chunk(lines);
        List<String> vectors = embedder.embed(chunks.stream().map(Chunk::content).toList());
        List<NewChunk> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            rows.add(new NewChunk(i, c.clauseNo(), c.title(), c.part(), c.content(), c.page(), vectors.get(i)));
        }
        completion.complete(() -> {
            if (contracts.transition(contract.id(), Status.INGESTING, Status.READY, null)) {
                contracts.replaceChunks(contract.id(), rows, embedder.modelName());
            }
        });
        log.info("Sözleşme indekslendi: {} chunk, maddeler {}", rows.size(), chunks.stream()
                .map(c -> c.clauseNo() == null ? "—" : c.clauseNo() + (c.part() > 1 ? "/" + c.part() : ""))
                .collect(Collectors.joining(" ")));
    }

    private static List<String> read(String storageUri) {
        Path path = Path.of(URI.create(storageUri));
        if (!Files.isRegularFile(path)) {
            throw new AmqpRejectAndDontRequeueException("Sözleşme dosyası yok: " + path.getFileName());
        }
        try {
            return PdfPages.read(path);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("PDF okunamadı: " + path.getFileName(), e);
        }
    }
}
