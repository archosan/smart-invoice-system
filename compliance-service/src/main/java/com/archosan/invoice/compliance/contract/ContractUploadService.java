package com.archosan.invoice.compliance.contract;

import com.archosan.invoice.compliance.contract.ContractRepository.Contract;
import com.archosan.invoice.compliance.contract.ContractRepository.Status;
import com.archosan.invoice.messaging.CorrelationScope;
import com.archosan.invoice.messaging.message.IngestContract;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sözleşme yükleme (B-45, FR-C1, US-11): dosya içerik adresli saklanır, tek transaction'da {@code INGESTING} kaydı ve
 * iç {@code IngestContract} komutu outbox'a yazılır. Aynı tedarikçide aralığı kesişen sözleşme varsa yükleme kabul
 * edilir ama uyarı döner (§4.3; B-46 o tarihteki faturalara {@code CONTRACT_CONFLICT} verir).
 *
 * <p>Aynı dosya ikinci kez gelirse yeni kayıt açılmaz, mevcut kayıt döner. Mevcut kayıt {@code FAILED} ise yeniden
 * indekslenir ({@code FAILED → INGESTING} + yeni komut; ör. Ollama'ya ulaşılamadığı için DLQ'ya düşmüştü); tedarikçi
 * ve tarihler ilk yüklemedekilerdir.
 */
@Service
public class ContractUploadService {

    private static final Logger log = LoggerFactory.getLogger(ContractUploadService.class);

    /** @param queued yeni bir {@code IngestContract} yazıldı (yeni kayıt ya da başarısız kaydın yeniden indekslenmesi) */
    public record UploadResult(UUID contractId, Status status, boolean duplicate, boolean queued,
            List<Contract> overlapping) {
    }

    private final ContractStorage storage;
    private final ContractRepository contracts;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    public ContractUploadService(ContractStorage storage, ContractRepository contracts, OutboxWriter outbox,
            PlatformTransactionManager transactionManager) {
        this.storage = storage;
        this.contracts = contracts;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws InvalidContractException VKN 10 hane değilse ya da başlangıç bitişten sonraysa; dosya okunmaz
     * @throws NotPdfException          içerik PDF değilse
     */
    public UploadResult upload(InputStream content, String vkn, LocalDate from, LocalDate to, String uploadedBy)
            throws IOException {
        if (vkn == null || !vkn.matches("\\d{10}")) {
            throw new InvalidContractException("supplierVkn 10 haneli olmalı");
        }
        if (from == null || to == null || from.isAfter(to)) {
            throw new InvalidContractException("validFrom ve validTo zorunlu, validFrom validTo'dan sonra olamaz");
        }
        StagedFile staged = storage.stage(content);
        try {
            return transactions.execute(status -> store(staged, vkn, from, to, uploadedBy));
        } finally {
            storage.discard(staged);
        }
    }

    private UploadResult store(StagedFile staged, String vkn, LocalDate from, LocalDate to, String uploadedBy) {
        UUID id = UUID.randomUUID();
        String uri = storage.uriFor(staged.sha256()).toString();
        if (!contracts.insertIfAbsent(id, vkn, from, to, uri, staged.sha256(), uploadedBy)) {
            Contract existing = contracts.findBySha256(staged.sha256())
                    .orElseThrow(() -> new IllegalStateException("Çakışan sözleşme bulunamadı: " + staged.sha256()));
            try (CorrelationScope ignored = CorrelationScope.open(existing.id())) {
                if (existing.status() == Status.FAILED
                        && contracts.transition(existing.id(), Status.FAILED, Status.INGESTING, null)) {
                    outbox.add(existing.id(), new IngestContract(existing.id(), existing.storageUri()));
                    log.info("Başarısız sözleşme aynı dosyayla yeniden indekslenecek");
                    return new UploadResult(existing.id(), Status.INGESTING, true, true, overlapping(existing.id(),
                            existing.supplierVkn(), existing.validFrom(), existing.validTo()));
                }
                log.info("Aynı sözleşme dosyası daha önce yüklenmiş: durum={}", existing.status());
                return new UploadResult(existing.id(), existing.status(), true, false, List.of());
            }
        }
        try (CorrelationScope ignored = CorrelationScope.open(id)) {
            outbox.add(id, new IngestContract(id, uri));
            storage.promote(staged);
            List<Contract> overlapping = overlapping(id, vkn, from, to);
            log.info("Sözleşme alındı: vkn={}, {}–{}, çakışan={}", vkn, from, to, overlapping.size());
            return new UploadResult(id, Status.INGESTING, false, true, overlapping);
        }
    }

    private List<Contract> overlapping(UUID id, String vkn, LocalDate from, LocalDate to) {
        return contracts.findOverlapping(id, vkn, from, to);
    }
}
