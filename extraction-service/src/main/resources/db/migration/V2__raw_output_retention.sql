-- Ham LLM çıktısının saklama süresi (B-47, FR-E6). Ham yanıt fatura metnini içerir (ticari ve kişisel veri); süresi
-- dolunca yalnız raw_output silinir, satır kalır: model, prompt sürümü, süre ve hata kalibrasyon istatistiği ve
-- denetim izi olarak tutulur. raw_output_purged_at silindiği anı, boş raw_output'u zaman aşımından ayırır.
ALTER TABLE extraction_runs ADD COLUMN raw_output_purged_at TIMESTAMPTZ;
-- Temizlik sorgusu: WHERE raw_output IS NOT NULL AND created_at < :before
CREATE INDEX extraction_runs_raw_output_age ON extraction_runs (created_at) WHERE raw_output IS NOT NULL;
