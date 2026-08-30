package com.archosan.invoice.rpa.submission;

import com.archosan.invoice.rpa.RpaProperties;
import com.archosan.invoice.rpa.portal.PortalAttemptFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Başarısız portal denemelerinin izi ({@code rpa_attempts}, B-37, FR-R6): düştüğü adım, hata ve ekran görüntüsü.
 * Görüntü {@code {screenshotDir}/{documentId}/{attempt}-{adım}.png}'ye yazılır; yazılamazsa satır yine kaydedilir.
 * Kendi transaction'ında (autocommit) yazılır: deneme sonucu ne olursa olsun (bekleme odası, DLQ) iz kalmalı.
 *
 * <p>Login sayfasının görüntüsünde şifre alanı tarayıcıda maskelidir ({@code type=password}); düz metin görünmez.
 */
@Repository
public class PortalAttempts {

    private static final Logger log = LoggerFactory.getLogger(PortalAttempts.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;
    private final Path screenshotDir;

    public PortalAttempts(JdbcClient jdbc, RpaProperties properties) {
        this.jdbc = jdbc;
        this.screenshotDir = properties.screenshotDir();
    }

    public void record(UUID documentId, int attemptNo, PortalAttemptFailedException failure) {
        String error = failure.getCause().toString();
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
        jdbc.sql("""
                        INSERT INTO rpa_attempts (document_id, attempt_no, failed_step, error, screenshot_path)
                        VALUES (:id, :attempt, :step, :error, :path)
                        """)
                .param("id", documentId)
                .param("attempt", attemptNo)
                .param("step", failure.step())
                .param("error", error)
                .param("path", saveScreenshot(documentId, attemptNo, failure))
                .update();
    }

    private String saveScreenshot(UUID documentId, int attemptNo, PortalAttemptFailedException failure) {
        if (failure.screenshot() == null) {
            return null;
        }
        Path file = screenshotDir.resolve(documentId.toString())
                .resolve(attemptNo + "-" + failure.step().replace(' ', '-') + ".png");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, failure.screenshot());
            return file.toString();
        } catch (IOException e) {
            log.warn("Ekran görüntüsü yazılamadı: {} ({})", file, e.toString());
            return null;
        }
    }
}
