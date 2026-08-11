package com.archosan.invoice.document.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ayarlar API'sinin işlemleri (B-39, FR-A2). Güncelleme {@code settings}'i ve her gerçek değişiklik için bir
 * {@code settings_changes} satırını tek transaction'da yazar; değeri aynı kalan anahtar iz bırakmaz. Commit'ten sonra
 * bu örneğin önbelleği boşaltılır, yeni eşik bir sonraki olayda geçerlidir. Okuma önbelleği atlar.
 */
@Service
public class SettingsAdmin {

    static final int CONFIDENCE_MAX_SCALE = 4;      // documents.confidence_score NUMERIC(5,4)
    static final int AMOUNT_MAX_SCALE = 2;

    private static final Logger log = LoggerFactory.getLogger(SettingsAdmin.class);

    /** Bir alan {@code null} ise güncellemede o ayar değişmez. */
    public record Values(BigDecimal confidenceThreshold, BigDecimal approvalAmountThreshold) {
    }

    private final JdbcClient jdbc;
    private final Settings settings;
    private final TransactionTemplate transactions;

    public SettingsAdmin(JdbcClient jdbc, Settings settings, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Values current() {
        Map<String, String> values = jdbc.sql("SELECT key, value FROM settings")
                .query((rs, n) -> Map.entry(rs.getString("key"), rs.getString("value")))
                .list().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
        return new Values(decimal(values, Settings.CONFIDENCE_THRESHOLD),
                decimal(values, Settings.APPROVAL_AMOUNT_THRESHOLD));
    }

    /** @throws InvalidSettingException alan yoksa ya da değer aralık dışındaysa; hiçbir şey yazılmaz */
    /** @param actor isteği yapan yönetici (B-43); {@code settings_changes.actor} */
    public Values update(Values changes, String actor) {
        Map<String, BigDecimal> requested = validate(changes);
        transactions.executeWithoutResult(status -> requested.forEach((key, value) -> write(key, value, actor)));
        settings.invalidate();
        return current();
    }

    private static Map<String, BigDecimal> validate(Values changes) {
        Map<String, BigDecimal> requested = new LinkedHashMap<>();
        if (changes.confidenceThreshold() != null) {
            BigDecimal value = changes.confidenceThreshold();
            if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw new InvalidSettingException("confidenceThreshold 0 ile 1 arasında olmalı");
            }
            if (value.stripTrailingZeros().scale() > CONFIDENCE_MAX_SCALE) {
                throw new InvalidSettingException(
                        "confidenceThreshold en fazla " + CONFIDENCE_MAX_SCALE + " ondalık basamak alabilir");
            }
            requested.put(Settings.CONFIDENCE_THRESHOLD, value);
        }
        if (changes.approvalAmountThreshold() != null) {
            BigDecimal value = changes.approvalAmountThreshold();
            if (value.signum() < 0) {
                throw new InvalidSettingException("approvalAmountThreshold 0 veya daha büyük olmalı");
            }
            if (value.stripTrailingZeros().scale() > AMOUNT_MAX_SCALE) {
                throw new InvalidSettingException(
                        "approvalAmountThreshold en fazla " + AMOUNT_MAX_SCALE + " ondalık basamak alabilir");
            }
            requested.put(Settings.APPROVAL_AMOUNT_THRESHOLD, value);
        }
        if (requested.isEmpty()) {
            throw new InvalidSettingException("En az bir ayar verilmeli: confidenceThreshold, approvalAmountThreshold");
        }
        return requested;
    }

    /** Satır kilitlenir; eş zamanlı iki güncelleme iz tablosunda doğru eski değeri görür. */
    private void write(String key, BigDecimal value, String actor) {
        String old = jdbc.sql("SELECT value FROM settings WHERE key = :key FOR UPDATE")
                .param("key", key).query(String.class).single();
        if (new BigDecimal(old).compareTo(value) == 0) {
            return;
        }
        String text = value.toPlainString();
        jdbc.sql("UPDATE settings SET value = :value, updated_at = now() WHERE key = :key")
                .param("key", key).param("value", text).update();
        jdbc.sql("""
                        INSERT INTO settings_changes (key, old_value, new_value, actor)
                        VALUES (:key, :old, :new, :actor)
                        """)
                .param("key", key).param("old", old).param("new", text).param("actor", actor).update();
        log.info("Ayar değişti: {} {} → {} ({})", key, old, text, actor);
    }

    private static BigDecimal decimal(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("settings tablosunda değer yok: " + key);
        }
        return new BigDecimal(value);
    }
}
