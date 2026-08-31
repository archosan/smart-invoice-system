-- Başarısız portal denemeleri (B-37, FR-R6): hangi adımda, hangi hatayla düştü ve o anki ekran görüntüsü.
-- attempt_no = portal_submissions.attempt_count; deneme bekleme odasından döndükçe artar.
CREATE TABLE rpa_attempts (
    id              BIGSERIAL   PRIMARY KEY,
    document_id     UUID        NOT NULL,
    attempt_no      INT         NOT NULL,
    failed_step     TEXT,
    error           TEXT,
    screenshot_path TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rpa_attempts_document ON rpa_attempts (document_id, attempt_no);
