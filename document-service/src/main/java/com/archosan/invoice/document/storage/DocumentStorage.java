package com.archosan.invoice.document.storage;

import com.archosan.invoice.document.DocumentProperties;
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
 * İçerik adresli PDF deposu (ADR-11): dosyalar {@code {storageDir}/{sha256}.pdf} yolunda değişmez saklanır.
 *
 * <p>Yükleme iki adımdır: {@link #stage} dosyayı geçici dizine akış halinde yazarken SHA-256'yı hesaplar (bellek dosya
 * boyutundan bağımsız); {@link #promote} onu atomik olarak yerine taşır. Geçici dizin aynı dosya sistemindedir ki
 * taşıma atomik olsun. Aynı hash'e ikinci kez taşımak zararsızdır, içerik aynıdır.
 */
@Component
public class DocumentStorage {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final Path root;
    private final Path staging;

    public DocumentStorage(DocumentProperties properties) {
        this.root = properties.storageDir().toAbsolutePath();
        this.staging = root.resolve(".staging");
        try {
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("Depolama dizini hazırlanamadı: " + root, e);
        }
    }

    /**
     * İçeriği geçici dizine yazar ve hash'ini hesaplar.
     *
     * @throws NotPdfException içerik {@code %PDF-} ile başlamıyorsa; bu durumda hiçbir dosya yazılmaz
     */
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

    /** Dosyayı {@code {sha256}.pdf} adıyla atomik olarak yerine taşır. */
    public void promote(StagedFile file) {
        try {
            Files.move(file.path(), pathFor(file.sha256()), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Dosya yerine taşınamadı: " + file.sha256(), e);
        }
    }

    /** Geçici dosyayı siler; yerine taşınmışsa bir şey yapmaz. */
    public void discard(StagedFile file) {
        try {
            Files.deleteIfExists(file.path());
        } catch (IOException e) {
            throw new UncheckedIOException("Geçici dosya silinemedi: " + file.path(), e);
        }
    }

    /** Mesajlarda taşınan adres, örn. {@code file:///data/documents/{sha256}.pdf}. */
    public URI uriFor(String sha256) {
        return pathFor(sha256).toUri();
    }

    Path root() {
        return root;
    }

    Path stagingDir() {
        return staging;
    }

    Path pathFor(String sha256) {
        return root.resolve(sha256 + ".pdf");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
