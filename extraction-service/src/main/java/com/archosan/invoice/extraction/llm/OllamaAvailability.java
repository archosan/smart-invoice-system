package com.archosan.invoice.extraction.llm;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.messaging.support.BackgroundLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Açık karar A1 (B-19): Ollama'ya hiç ulaşılamazsa fatura denemeleri boşuna tüketilmesin, faturalar topluca
 * {@code NEEDS_REVIEW}'a düşmesin. İlk bağlantı hatasında extraction dinleyicisi durdurulur (mesajlar kuyrukta
 * dokunulmadan bekler), Ollama {@code availability-probe-interval} aralıkla {@code GET /api/tags} ile yoklanır, yanıt
 * gelince dinleyici yeniden başlar. Durdurma ayrı thread'dedir: dinleyici kendi thread'inden durdurulamaz.
 *
 * <p>Health: Ollama yokken {@code UNKNOWN} ve ayrıntı; servisin genel durumu {@code UP} kalır (servis sağlıklı, yalnızca
 * bağımlılığı bekliyor).
 */
@Component("ollama")
public class OllamaAvailability implements HealthIndicator {

    static final String CONTAINER_BEAN = "extractInvoiceContainer";

    private static final Logger log = LoggerFactory.getLogger(OllamaAvailability.class);

    private final ApplicationContext context;
    private final RestClient probe;
    private final Duration probeInterval;
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private volatile BackgroundLoop prober;
    private volatile String lastReason;

    public OllamaAvailability(ApplicationContext context, ExtractionProperties properties,
            @Value("${spring.ai.ollama.base-url}") String baseUrl) {
        this.context = context;
        this.probe = RestClient.create(baseUrl);
        this.probeInterval = properties.llm().availabilityProbeInterval();
    }

    public boolean isAvailable() {
        return !unavailable.get();
    }

    /** Dinleyiciyi durdurur ve yoklamayı başlatır; zaten durduysa bir şey yapmaz. */
    public void markUnavailable(String reason) {
        if (!unavailable.compareAndSet(false, true)) {
            return;
        }
        lastReason = reason;
        log.warn("Ollama'ya ulaşılamıyor, extraction dinleyicisi durduruluyor; {} aralıkla yoklanacak: {}",
                probeInterval, reason);
        Thread.ofVirtual().name("ollama-unavailable").start(() -> {
            container().stop();
            BackgroundLoop loop = new BackgroundLoop("ollama-probe", probeInterval, Duration.ofSeconds(5),
                    this::probeOnce);
            prober = loop;
            loop.start();
        });
    }

    /** Tek yoklama turu; BackgroundLoop sözleşmesi gereği her zaman {@code false} (aralık kadar bekle). */
    private boolean probeOnce() {
        if (!unavailable.get()) {
            return false;
        }
        try {
            probe.get().uri("/api/tags").retrieve().toBodilessEntity();
        } catch (RuntimeException stillDown) {
            log.info("Ollama hâlâ yok: {}", stillDown.getMessage());
            return false;
        }
        log.info("Ollama yeniden erişilebilir, extraction dinleyicisi başlatılıyor");
        unavailable.set(false);
        container().start();
        BackgroundLoop loop = prober;
        if (loop != null) {
            // Kendi thread'inden durdurulamaz; ayrı thread'de durdurulur.
            Thread.ofVirtual().start(loop::stop);
        }
        return false;
    }

    private SimpleMessageListenerContainer container() {
        return context.getBean(CONTAINER_BEAN, SimpleMessageListenerContainer.class);
    }

    @Override
    public Health health() {
        return isAvailable()
                ? Health.up().build()
                : Health.unknown().withDetail("reason", String.valueOf(lastReason)).build();
    }
}
