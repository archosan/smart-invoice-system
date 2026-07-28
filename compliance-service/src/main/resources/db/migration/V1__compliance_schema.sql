-- Sözleşmeler ve chunk'ları (B-45, FR-C1). vector eklentisi compliance_db'de init betiğiyle superuser tarafından
-- kurulur (infra/postgres/init/01-databases.sql); servis kullanıcısı yalnızca tipi kullanır.
CREATE TABLE contracts (
    id             UUID        PRIMARY KEY,
    supplier_vkn   CHAR(10)    NOT NULL CHECK (supplier_vkn ~ '^[0-9]{10}$'),
    valid_from     DATE        NOT NULL,
    valid_to       DATE        NOT NULL,
    storage_uri    TEXT        NOT NULL,
    file_sha256    CHAR(64)    NOT NULL UNIQUE CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    status         TEXT        NOT NULL CHECK (status IN ('INGESTING', 'READY', 'FAILED')),
    failure_reason TEXT,                                    -- FAILED ise neden
    uploaded_by    TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_from <= valid_to),
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);
-- B-46 seçimi: WHERE supplier_vkn = ? AND ? BETWEEN valid_from AND valid_to AND status = 'READY'
CREATE INDEX contracts_selection ON contracts (supplier_vkn, valid_from, valid_to);

CREATE TABLE contract_chunks (
    id              BIGSERIAL    PRIMARY KEY,
    contract_id     UUID         NOT NULL REFERENCES contracts (id),
    chunk_index     INT          NOT NULL,              -- belgedeki sıra
    clause_no       TEXT,                               -- "4", "4.1"; giriş ve kalıpsız bölmede NULL
    title           TEXT,                               -- madde başlığı
    part            INT          NOT NULL DEFAULT 1,    -- uzun madde bölündüyse parça no
    content         TEXT         NOT NULL,              -- normalize metin; alt madde ve parçalarda başlık önde
    page            INT          NOT NULL,              -- chunk'ın başladığı sayfa
    embedding       vector(1024) NOT NULL,              -- bge-m3, normalize (kosinüs)
    embedding_model TEXT         NOT NULL,              -- model değişirse yeniden indeksleme tespiti
    -- Bu kısıtın indeksi FK'yi ve filtreli tam taramayı da karşılar (ADR-09):
    -- WHERE contract_id = ? ORDER BY embedding <=> :q; HNSW yok.
    UNIQUE (contract_id, chunk_index)
);
