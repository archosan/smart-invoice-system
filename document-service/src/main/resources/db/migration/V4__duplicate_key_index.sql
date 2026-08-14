-- İçerik bazlı mükerrer şüphesi (B-41, FR-D8, US-03): eşleşme anahtarı kırpılmış VKN ve kırpılmış, büyük harfli fatura
-- no'dur. Sorgu bu ifadelerle yapılır; V1'deki (supplier_vkn, invoice_no) indeksi kullanılamaz. Unique değil:
-- mükerrer belgeler kayıt olarak kalır, karar insanındır.
CREATE INDEX documents_duplicate_key ON documents (btrim(supplier_vkn), upper(btrim(invoice_no)));
