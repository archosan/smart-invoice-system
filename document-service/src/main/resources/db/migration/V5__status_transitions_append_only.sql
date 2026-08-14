-- status_transitions yalnızca eklenir (B-42, NFR-05, FR-D6): durum geçmişi denetim kaydıdır, güncellenmez, silinmez.
-- İki katman: (1) uygulama kullanıcısının UPDATE/DELETE/TRUNCATE yetkisi geri alınır; (2) yetki yanlışlıkla geri
-- verilse de değişikliği reddeden tetikleyiciler. Migration'ı çalıştıran kullanıcı tablonun sahibidir (document_user)
-- ve yetkiyi kendine geri verebilir; koruma uygulama hatasına ve kazaya karşıdır, kötü niyetli bir DB sahibine karşı
-- değil (ayrı sahip rolü bilinçli olarak seçilmedi, DECISIONS.md B-42).
REVOKE UPDATE, DELETE, TRUNCATE ON status_transitions FROM CURRENT_USER;

CREATE FUNCTION status_transitions_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'status_transitions yalnızca eklenir (NFR-05): % reddedildi', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER status_transitions_no_update_delete
    BEFORE UPDATE OR DELETE ON status_transitions
    FOR EACH ROW EXECUTE FUNCTION status_transitions_append_only();

CREATE TRIGGER status_transitions_no_truncate
    BEFORE TRUNCATE ON status_transitions
    FOR EACH STATEMENT EXECUTE FUNCTION status_transitions_append_only();
