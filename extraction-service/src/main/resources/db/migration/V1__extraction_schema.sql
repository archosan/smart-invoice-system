-- extraction_db şeması (DECISIONS.md §4.2, B-21). outbox (V0_1) ve inbox (V0_2) common-messaging'den gelir.
-- Her LLM denemesi bir satır (FR-E6): LLM'in neyi neden yanlış çıkardığını açıklamak ve kalibrasyon (B-29) için.
-- Satırlar sonuçla aynı transaction'da, inbox kaydıyla birlikte yazılır; tekrar teslimde bir kez yazılır.
CREATE TABLE extraction_runs (
    id             BIGSERIAL   PRIMARY KEY,
    document_id    UUID        NOT NULL,
    attempt_no     INT         NOT NULL,
    model          TEXT        NOT NULL,
    prompt_version TEXT        NOT NULL,
    raw_output     TEXT,                       -- ham LLM yanıtı; zaman aşımında boş
    parse_error    TEXT,                       -- bozuk çıktı veya zaman aşımı; başarılı denemede boş
    duration_ms    INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, attempt_no)
);
