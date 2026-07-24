package com.archosan.invoice.compliance.check;

import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.consumer.InboxCompletion;
import com.archosan.invoice.messaging.consumer.LongRunningMessageHandler;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code compliance.check-compliance.q} (B-46, FR-C2–C4): uzun iş. Kontrol ({@link ComplianceEvaluator}; embedding,
 * LLM) transaction dışında; sonuç inbox kaydıyla aynı kısa transaction'da {@code compliance_checks}'e ve
 * {@code ComplianceCompleted} olarak outbox'a yazılır. Ollama'ya ulaşılamaması geçicidir: mesaj geri konur, teslim
 * limitinden sonra DLQ → document-service kaydı {@code PENDING_APPROVAL}'a alır (v1'den beri).
 */
@Component
class CheckComplianceHandler implements LongRunningMessageHandler<CheckCompliance> {

    private static final Logger log = LoggerFactory.getLogger(CheckComplianceHandler.class);

    private final ComplianceEvaluator evaluator;
    private final ComplianceCheckRepository checks;
    private final OutboxWriter outbox;

    CheckComplianceHandler(ComplianceEvaluator evaluator, ComplianceCheckRepository checks, OutboxWriter outbox) {
        this.evaluator = evaluator;
        this.checks = checks;
        this.outbox = outbox;
    }

    @Override
    public void handle(MessageEnvelope envelope, CheckCompliance command, InboxCompletion completion) {
        ComplianceEvaluator.Evaluation evaluation = evaluator.evaluate(command);
        completion.complete(() -> {
            checks.save(command.documentId(), evaluation);
            outbox.add(command.documentId(), new ComplianceCompleted(command.documentId(), evaluation.result(),
                    evaluation.contractId(), evaluation.findings()));
        });
        log.info("Uyum kontrolü: {}, sözleşme={}, bulgu={} (güvenilmez {})", evaluation.result(),
                evaluation.contractId(), evaluation.findings().size(),
                evaluation.findings().stream().filter(f -> !f.reliable()).count());
    }
}
