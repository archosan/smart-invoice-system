-- Ortak inbox tablosu (DECISIONS.md §5, ADR-05). consumer = kuyruk adı.
CREATE TABLE inbox (
    message_id    UUID NOT NULL,
    consumer      TEXT NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer)
);
-- 30 günlük temizlik sorgusu için.
CREATE INDEX inbox_processed_at ON inbox (processed_at);
