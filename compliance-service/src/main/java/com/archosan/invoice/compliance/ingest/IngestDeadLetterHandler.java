package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.compliance.contract.ContractRepository;
import com.archosan.invoice.compliance.contract.ContractRepository.Status;
import com.archosan.invoice.messaging.consumer.DeadLetter;
import com.archosan.invoice.messaging.message.IngestContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code compliance.ingest-contract.dlq} (§4.3): sözleşme {@code INGESTING → FAILED}, neden DLQ'ya düşüş; alarm
 * {@code ERROR} logudur (v1 ile aynı, metrik B-48'de). Mesaj ack edilir; kullanıcı aynı dosyayı yeniden yükleyerek
 * yeniden indeksletir. Çözülemeyen mesaj yalnız loglanır.
 */
@Component
class IngestDeadLetterHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestDeadLetterHandler.class);

    private final ContractRepository contracts;

    IngestDeadLetterHandler(ContractRepository contracts) {
        this.contracts = contracts;
    }

    void handle(DeadLetter deadLetter) {
        if (!(deadLetter.payload() instanceof IngestContract command)) {
            log.error("Sözleşme indeksleme DLQ'sunda çözülemeyen mesaj: tip={}, neden={}", deadLetter.type(),
                    deadLetter.deathReason());
            return;
        }
        String reason = "DLQ: " + deadLetter.deathReason() + " (" + deadLetter.deathQueue() + ")";
        boolean failed = contracts.transition(command.contractId(), Status.INGESTING, Status.FAILED, reason);
        log.error("Sözleşme indekslenemedi, DLQ'ya düştü: contractId={}, {}, durum {}", command.contractId(), reason,
                failed ? "FAILED yapıldı" : "zaten INGESTING değildi");
    }
}
