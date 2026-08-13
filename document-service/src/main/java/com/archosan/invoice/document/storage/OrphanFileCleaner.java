package com.archosan.invoice.document.storage;

import com.archosan.invoice.document.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sahipsiz dosya temizliği (B-15): kaydı olmayan {@code {sha256}.pdf} dosyalarını ve çökmeden kalan geçici dosyaları
 * siler. Yalnızca {@code gracePeriod}'dan eski dosyalara dokunur: yüklemede dosya commit'ten önce yerine taşındığı için
 * commit olmak üzere olan kaydın dosyası silinmemeli. Hash adlı olmayan dosyalara dokunulmaz. Birden fazla örnek aynı
 * anda çalışsa da zararsızdır.
 */
@Component
public class OrphanFileCleaner {

    private static final Logger log = LoggerFactory.getLogger(OrphanFileCleaner.class);
    private static final Pattern STORED_NAME = Pattern.compile("^([0-9a-f]{64})\\.pdf$");
    private static final int QUERY_CHUNK = 500;

    public record Result(int orphansDeleted, int stagingDeleted) {
    }

    private final DocumentStorage storage;
    private final JdbcClient jdbc;
    private final Duration gracePeriod;

    public OrphanFileCleaner(DocumentStorage storage, JdbcClient jdbc, DocumentProperties properties) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.gracePeriod = properties.orphanCleanup().gracePeriod();
    }

    @Scheduled(cron = "${invoice.document.orphan-cleanup.cron:0 0 3 * * *}")
    void scheduledCleanUp() {
        cleanUp();
    }

    public Result cleanUp() {
        Instant cutoff = Instant.now().minus(gracePeriod);
        List<Path> candidates = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        try (Stream<Path> files = Files.list(storage.root())) {
            for (Path file : (Iterable<Path>) files::iterator) {
                var matcher = STORED_NAME.matcher(file.getFileName().toString());
                if (Files.isRegularFile(file) && matcher.matches() && isOlderThan(file, cutoff)) {
                    candidates.add(file);
                    hashes.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Depolama dizini okunamadı", e);
        }

        Set<String> known = knownHashes(hashes);
        int orphans = 0;
        for (Path file : candidates) {
            String sha = file.getFileName().toString().substring(0, 64);
            if (!known.contains(sha) && delete(file)) {
                orphans++;
            }
        }
        int staging = deleteStaleStaging(cutoff);
        if (orphans + staging > 0) {
            log.info("Sahipsiz dosya temizliği: {} sahipsiz PDF, {} geçici dosya silindi", orphans, staging);
        }
        return new Result(orphans, staging);
    }

    private Set<String> knownHashes(List<String> hashes) {
        Set<String> known = new HashSet<>();
        for (int i = 0; i < hashes.size(); i += QUERY_CHUNK) {
            known.addAll(jdbc.sql("SELECT file_sha256 FROM documents WHERE file_sha256 IN (:hashes)")
                    .param("hashes", hashes.subList(i, Math.min(hashes.size(), i + QUERY_CHUNK)))
                    .query(String.class).list());
        }
        return known;
    }

    private int deleteStaleStaging(Instant cutoff) {
        int deleted = 0;
        try (Stream<Path> files = Files.list(storage.stagingDir())) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(file) && isOlderThan(file, cutoff) && delete(file)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Geçici dizin okunamadı", e);
        }
        return deleted;
    }

    private static boolean isOlderThan(Path file, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Dosya silinemedi: {}", file, e);
            return false;
        }
    }
}
