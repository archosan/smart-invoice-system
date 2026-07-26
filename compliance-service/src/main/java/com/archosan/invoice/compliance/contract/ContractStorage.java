package com.archosan.invoice.compliance.contract;

import com.archosan.invoice.compliance.ComplianceProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * İçerik adresli sözleşme deposu: {@code {storageDir}/{sha256}.pdf}, değişmez (document-service'in deposuyla aynı
 * desen, ADR-11). Dosya geçici dizine akış halinde yazılırken hash'lenir, commit'ten önce atomik olarak yerine taşınır.
 */
@Component
public class ContractStorage {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final Path root;
    private final Path staging;

    public ContractStorage(ComplianceProperties properties) {
        this.root = properties.storageDir().toAbsolutePath();
        this.staging = root.resolve(".staging");
        try {
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("Sözleşme dizini hazırlanamadı: " + root, e);
        }
    }

    /** @throws NotPdfException içerik {@code %PDF-} ile başlamıyorsa; dosya yazılmaz */
    public StagedFile stage(InputStream content) throws IOException {
        PushbackInputStream in = new PushbackInputStream(content, PDF_MAGIC.length);
        byte[] head = in.readNBytes(PDF_MAGIC.length);
        if (!Arrays.equals(head, PDF_MAGIC)) {
            throw new NotPdfException();
        }
        in.unread(head);

        MessageDigest digest = sha256();
        Path temp = Files.createTempFile(staging, "upload-", ".pdf");
        try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
            Files.copy(digesting, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return new StagedFile(temp, HexFormat.of().formatHex(digest.digest()));
    }

    public void promote(StagedFile file) {
        try {
            Files.move(file.path(), root.resolve(file.sha256() + ".pdf"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Dosya yerine taşınamadı: " + file.sha256(), e);
        }
    }

    public void discard(StagedFile file) {
        try {
            Files.deleteIfExists(file.path());
        } catch (IOException e) {
            throw new UncheckedIOException("Geçici dosya silinemedi: " + file.path(), e);
        }
    }

    /** {@code IngestContract}'ta taşınan adres. */
    public URI uriFor(String sha256) {
        return root.resolve(sha256 + ".pdf").toUri();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
