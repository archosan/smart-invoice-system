-- Ayar değişikliklerinin izi (B-39, FR-A2). Yalnız eklenir; settings tablosu güncel değeri, bu tablo kimin neyi ne
-- zaman değiştirdiğini tutar. actor B-43'e kadar sabit 'admin'.
CREATE TABLE settings_changes (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key        TEXT        NOT NULL REFERENCES settings (key),
    old_value  TEXT        NOT NULL,
    new_value  TEXT        NOT NULL,
    actor      TEXT        NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX settings_changes_key ON settings_changes (key, changed_at);
