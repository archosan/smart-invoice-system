package com.archosan.invoice.compliance.api;

import com.archosan.invoice.compliance.contract.ContractRepository;
import com.archosan.invoice.compliance.contract.ContractRepository.ChunkSummary;
import com.archosan.invoice.compliance.contract.ContractRepository.Contract;
import com.archosan.invoice.compliance.contract.ContractUploadService;
import com.archosan.invoice.compliance.contract.ContractUploadService.UploadResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sözleşme API'si (B-45, FR-C1, US-11, §4.3). Yükleme {@code EXPERT}, okuma giriş yapmış herkes (ADR-22).
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractUploadService uploads;
    private final ContractRepository contracts;

    public ContractController(ContractUploadService uploads, ContractRepository contracts) {
        this.uploads = uploads;
        this.contracts = contracts;
    }

    /**
     * 202 yeni kayıt ya da başarısız sözleşmenin yeniden indekslenmesi ({@code Location} ile), 200 aynı dosya zaten
     * var. Aralığı kesişen sözleşme varsa {@code warnings} doludur, yükleme yine kabul edilir.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
            @RequestParam("supplierVkn") String supplierVkn,
            @RequestParam("validFrom") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
            @RequestParam("validTo") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo,
            Authentication authentication) throws IOException {
        UploadResult result;
        try (InputStream content = file.getInputStream()) {
            result = uploads.upload(content, supplierVkn, validFrom, validTo, authentication.getName());
        }
        UploadResponse body = new UploadResponse(result.contractId(), result.status().name(), result.duplicate(),
                result.overlapping().stream()
                        .map(c -> "Aynı tedarikçide aralığı kesişen sözleşme: " + c.id() + " (" + c.validFrom()
                                + " – " + c.validTo() + ", " + c.status() + "); kesişen tarihli faturalar "
                                + "CONTRACT_CONFLICT alır")
                        .toList());
        if (!result.queued()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.accepted()
                .location(ServletUriComponentsBuilder.fromCurrentRequestUri()
                        .path("/{id}").buildAndExpand(result.contractId()).toUri())
                .body(body);
    }

    /** {@code vkn} verilirse yalnız o tedarikçinin; geçerlilik başlangıcına göre yeniden eskiye. */
    @GetMapping
    public List<Contract> list(@RequestParam(required = false) String vkn) {
        return contracts.findByVkn(vkn == null || vkn.isBlank() ? null : vkn.strip());
    }

    /** Sözleşme, indeksleme durumu ve chunk özetleri (embedding'siz). */
    @GetMapping("/{id}")
    public ContractDetail get(@PathVariable UUID id) {
        Contract contract = contracts.find(id).orElseThrow(() -> new ContractNotFoundException(id));
        return new ContractDetail(contract, contracts.chunks(id));
    }

    public record UploadResponse(UUID contractId, String status, boolean duplicate, List<String> warnings) {
    }

    public record ContractDetail(Contract contract, List<ChunkSummary> chunks) {
    }
}
