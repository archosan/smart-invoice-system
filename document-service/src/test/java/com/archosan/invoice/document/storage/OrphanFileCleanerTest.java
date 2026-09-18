package com.archosan.invoice.document.storage;

import com.archosan.invoice.document.DocumentIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Varsayılan bekleme süresi 1 saat. */
class OrphanFileCleanerTest extends DocumentIntegrationTest {

    @Autowired
    private OrphanFileCleaner cleaner;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyStorage() throws IOException {
        try (Stream<Path> files = Files.walk(STORAGE_DIR)) {
            files.filter(Files::isRegularFile).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void deletesOnlyOldOrphansAndStaleStagingFiles() throws IOException {
        Path withRecord = stored(sha('a'), Duration.ofHours(5));
        insertDocument(sha('a'));
        Path oldOrphan = stored(sha('b'), Duration.ofHours(2));
        Path freshOrphan = stored(sha('c'), Duration.ofMinutes(10));    // commit'i bekliyor olabilir
        Path notOurs = file(STORAGE_DIR.resolve("notlar.txt"), Duration.ofDays(3));
        Path staleStaging = file(STORAGE_DIR.resolve(".staging/upload-1.pdf"), Duration.ofHours(2));
        Path activeStaging = file(STORAGE_DIR.resolve(".staging/upload-2.pdf"), Duration.ofMinutes(1));

        OrphanFileCleaner.Result result = cleaner.cleanUp();

        assertThat(result).isEqualTo(new OrphanFileCleaner.Result(1, 1));
        assertThat(withRecord).exists();
        assertThat(oldOrphan).doesNotExist();
        assertThat(freshOrphan).exists();
        assertThat(notOurs).exists();
        assertThat(staleStaging).doesNotExist();
        assertThat(activeStaging).exists();
    }

    @Test
    void nothingToCleanIsNoOp() {
        assertThat(cleaner.cleanUp()).isEqualTo(new OrphanFileCleaner.Result(0, 0));
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static Path stored(String sha, Duration age) throws IOException {
        return file(STORAGE_DIR.resolve(sha + ".pdf"), age);
    }

    private static Path file(Path path, Duration age) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "%PDF-1.7");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
        return path;
    }

    private void insertDocument(String sha) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status)
                        VALUES (:id, :sha, 'file:///x.pdf', 'RECEIVED')
                        """)
                .param("id", id).param("sha", sha).update();
    }
}
