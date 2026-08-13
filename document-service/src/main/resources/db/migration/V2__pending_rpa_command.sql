-- Bekleyen PostToPortal komutunun kimliği (B-38, A4). Yönetici bir kaydı yeniden işletince yeni bir tur başlar; önceki
-- turdan gecikerek gelen DLQ mesajı yeni turu RPA_FAILED yapmasın diye DLQ işleyicisi mesaj kimliğini bununla
-- karşılaştırır. Durum koşulu da version da eski turu ayırt edemez.
ALTER TABLE documents ADD COLUMN pending_rpa_command_id UUID;
