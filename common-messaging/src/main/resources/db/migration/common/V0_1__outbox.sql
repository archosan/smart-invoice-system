-- Ortak outbox tablosu (DECISIONS.md §5, ADR-04). common-messaging'den gelir; her serviste birebir aynıdır.
-- Ortak migration'lar V0_x aralığındadır, servis migration'ları V1'den başlar.
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,          -- = messageId
    aggregate_id  UUID NOT NULL,             -- = documentId
    message_type  TEXT NOT NULL,
    exchange      TEXT NOT NULL,
    routing_key   TEXT NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT NOT NULL DEFAULT 0
);
CREATE INDEX outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
