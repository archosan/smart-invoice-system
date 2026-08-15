package com.archosan.invoice.extraction.api;

import com.archosan.invoice.extraction.runs.ExtractionRunRepository;
import com.archosan.invoice.extraction.runs.ExtractionRunRepository.ExtractionRun;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Ham LLM çıktısını inceleme (B-47, FR-E6): bir belgenin bütün LLM denemeleri, sırayla; model, prompt sürümü, süre,
 * ayrıştırma hatası ve ham yanıt. Belge bu serviste bilinmediği için kayıt yoksa boş liste döner (404 değil). Saklama
 * süresi dolmuş denemede {@code rawOutput} boş, {@code rawOutputPurgedAt} doludur.
 */
@RestController
@RequestMapping("/api/v1/extraction-runs")
public class ExtractionRunController {

    private final ExtractionRunRepository runs;

    public ExtractionRunController(ExtractionRunRepository runs) {
        this.runs = runs;
    }

    @GetMapping
    public List<ExtractionRun> list(@RequestParam UUID documentId) {
        return runs.findByDocument(documentId);
    }
}
