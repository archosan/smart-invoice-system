-- Deneme bütçesi tur bazında (B-38): yönetici bir faturayı yeniden işletince yeni bir PostToPortal (yeni kimlik) gelir
-- ve yeni tur kendi deneme hakkıyla başlar. Bekleme odasından dönen mesaj aynı kimliği taşır, turu sürdürür.
-- attempt_count bütün turların toplamı kalır (rpa_attempts.attempt_no).
ALTER TABLE portal_submissions ADD COLUMN command_id UUID;
ALTER TABLE portal_submissions ADD COLUMN round_attempts INT NOT NULL DEFAULT 0;
