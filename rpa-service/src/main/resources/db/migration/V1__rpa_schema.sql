-- rpa_db şeması (DECISIONS.md §4.4, B-25). outbox (V0_1) ve inbox (V0_2) common-messaging'den gelir.
-- "Portala girmeye başladım" bilgisi: forma yazmadan önce IN_PROGRESS commit edilir; giriş bitince SUBMITTED,
-- inbox ve RpaCompleted aynı transaction'da yazılır. FOUND_EXISTING v2'de ön aramayla (B-35) gelir.
-- rpa_attempts (FR-R6, ekran görüntüleri) v2'de (B-37).
CREATE TABLE portal_submissions (
    document_id   UUID        PRIMARY KEY,
    supplier_vkn  CHAR(10)    NOT NULL,
    invoice_no    TEXT        NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'FOUND_EXISTING')),
    portal_ref_no TEXT,
    attempt_count INT         NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'IN_PROGRESS') = (portal_ref_no IS NULL))
);
