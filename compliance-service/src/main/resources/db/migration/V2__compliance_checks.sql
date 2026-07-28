-- Her uyum kontrolünün izi (B-46, FR-C4): seçilen sözleşme, sonuç, bulgular, getirilen chunk'lar ve model. Aynı
-- belge yeniden kontrol edilirse (yönetici yeniden işletmesi) yeni satır eklenir; son satır güncel sonuçtur.
CREATE TABLE compliance_checks (
    id                  BIGSERIAL   PRIMARY KEY,
    document_id         UUID        NOT NULL,
    contract_id         UUID        REFERENCES contracts (id),
    result              TEXT        NOT NULL CHECK (result IN ('COMPLIANT', 'NON_COMPLIANT', 'NO_CONTRACT',
                                                               'CONTRACT_CONFLICT')),
    findings            JSONB       NOT NULL,
    retrieved_chunk_ids BIGINT[]    NOT NULL DEFAULT '{}',
    model               TEXT,                                -- LLM'e gidilmediyse (seçim SQL'de bitti) NULL
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX compliance_checks_document ON compliance_checks (document_id, created_at);
