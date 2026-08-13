-- document_db şeması (DECISIONS.md §4.1, B-09). outbox (V0_1) ve inbox (V0_2) common-messaging'den gelir.
--
-- Kod kolonları TEXT + CHECK: geçersiz değer DB'de reddedilir, değer eklemek tek ALTER'dır.
-- LLM'den gelen değerlerin kopyaları (supplier_vkn, currency, grand_total) bilerek gevşek tiplidir: kötü çıktı da
-- kaydedilip NEEDS_REVIEW'a gidebilsin, INSERT hatasıyla DLQ'ya düşmesin, tutar sessizce yuvarlanmasın. Resmi veri
-- invoice_data.fields'tadır; doğrulama kurallarda kalır (FR-E3).
-- updated_at ve version uygulamada, koşullu UPDATE içinde güncellenir; trigger yok.

CREATE TABLE documents (
    id               UUID        PRIMARY KEY,
    file_sha256      CHAR(64)    NOT NULL UNIQUE                          -- US-02; bizim hesapladığımız hash
                     CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    storage_uri      TEXT        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN (
                         'RECEIVED', 'EXTRACTED', 'VALIDATED', 'COMPLIANCE_CHECKED', 'QUEUED_FOR_RPA', 'POSTED',
                         'NEEDS_REVIEW', 'PENDING_APPROVAL', 'REJECTED', 'DUPLICATE_SUSPECTED', 'RPA_FAILED')),
    supplier_vkn     TEXT,                                                -- LLM çıktısı; 10 hane kuralı FR-E3'te
    invoice_no       TEXT,
    invoice_date     DATE,
    grand_total      NUMERIC,                                             -- ölçeksiz: yuvarlama yok
    currency         TEXT,
    confidence_score NUMERIC(5,4),
    portal_ref_no    TEXT,
    uploaded_by      TEXT,                                                -- onaycı kendi yüklediğini onaylayamaz
    version          INT         NOT NULL DEFAULT 0,                      -- If-Match, koşullu geçiş
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX documents_vkn_invoice ON documents (supplier_vkn, invoice_no);   -- unique değil (US-03)
CREATE INDEX documents_status      ON documents (status, updated_at);         -- liste + takılı kayıt dedektörü

CREATE TABLE invoice_data (                                               -- faturanın "resmi" verisi
    document_id  UUID        PRIMARY KEY REFERENCES documents (id),
    fields       JSONB       NOT NULL,
    rule_results JSONB       NOT NULL,
    source       TEXT        NOT NULL CHECK (source IN ('LLM', 'EXPERT_CORRECTION')),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE compliance_results (                                         -- onaycı ekranının kaynağı (US-06)
    document_id UUID        PRIMARY KEY REFERENCES documents (id),
    result      TEXT        NOT NULL CHECK (result IN (
                    'COMPLIANT', 'NON_COMPLIANT', 'NO_CONTRACT', 'CONTRACT_CONFLICT')),
    findings    JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE status_transitions (                                         -- append-only; v2'de UPDATE/DELETE yetkisi geri alınır
    id            BIGSERIAL   PRIMARY KEY,
    document_id   UUID        NOT NULL REFERENCES documents (id),
    from_status   TEXT        CHECK (from_status IN (
                      'RECEIVED', 'EXTRACTED', 'VALIDATED', 'COMPLIANCE_CHECKED', 'QUEUED_FOR_RPA', 'POSTED',
                      'NEEDS_REVIEW', 'PENDING_APPROVAL', 'REJECTED', 'DUPLICATE_SUSPECTED', 'RPA_FAILED')),
    to_status     TEXT        NOT NULL CHECK (to_status IN (
                      'RECEIVED', 'EXTRACTED', 'VALIDATED', 'COMPLIANCE_CHECKED', 'QUEUED_FOR_RPA', 'POSTED',
                      'NEEDS_REVIEW', 'PENDING_APPROVAL', 'REJECTED', 'DUPLICATE_SUSPECTED', 'RPA_FAILED')),
    trigger_event TEXT        NOT NULL,
    message_id    UUID,
    actor         TEXT        NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX status_transitions_document ON status_transitions (document_id, created_at);   -- FK + geçmiş (US-12)

CREATE TABLE dead_letters (                                               -- DLQ park tablosu + geç olaylar
    id           UUID        PRIMARY KEY,
    kind         TEXT        NOT NULL CHECK (kind IN ('DLQ', 'LATE_EVENT')),
    source_queue TEXT        NOT NULL,
    message_type TEXT        NOT NULL,
    message_id   UUID        NOT NULL,
    document_id  UUID,
    body         JSONB       NOT NULL,
    x_death      JSONB,
    status       TEXT        NOT NULL CHECK (status IN ('OPEN', 'REPROCESSED', 'IGNORED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX dead_letters_status ON dead_letters (status, created_at);   -- yönetici listesi (FR-A1)

CREATE TABLE settings (                                                   -- FR-A2; bellekte birkaç saniye önbelleklenir
    key        TEXT        PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Yer tutucu başlangıç değerleri; A5'te 10 sentetik faturayla ölçülüp yeni migration veya ayar API'siyle değişir.
-- approval_amount_threshold v2'de kullanılır (VALIDATED → PENDING_APPROVAL, tutar > eşik).
INSERT INTO settings (key, value) VALUES
    ('confidence_threshold', '0.80'),
    ('approval_amount_threshold', '100000.00');
