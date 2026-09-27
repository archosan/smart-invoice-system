package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaIntegrationTest;
import com.archosan.invoice.rpa.RpaProperties;
import com.archosan.invoice.rpa.RpaServiceApplication;
import com.archosan.invoice.rpa.portal.PlaywrightPortal;
import com.archosan.invoice.rpa.portal.PortalValues;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR-R8, ADR-08 (B-27): aynı broker, DB ve portala bağlı iki rpa-service örneği. {@code rpa.post-to-portal.q}
 * single active consumer olduğu için yalnızca ilk bağlanan örnek tüketir, ikincisi yedekte bekler; aktif örnek
 * durunca yedek devralır. Hiçbir fatura aynı anda iki kez işlenmez ve her fatura bir kez girilir.
 *
 * <p>Devir anı: aktif örneğin listener'ı durunca Spring önce {@code basic.cancel} gönderir, sonra sürmekte olan girişi
 * bitirip ack'ler; broker yedeği cancel'da aktif yapar. Bu kısa aralıkta iki örnek <em>farklı</em> faturalarda birlikte
 * çalışabilir (B-27'de görüldü, kabul edildi; kesin tek bot v2'de kilitle, FR-R7). Bu yüzden "en fazla bir giriş"
 * yalnızca devirden önce, "aynı fatura iki kez değil" her zaman doğrulanır.
 *
 * <p>A örneği testin context'idir, B örneği test içinde aynı ayarlarla açılır. Portal girişi, hangi örneğin girdiğini
 * ve eşzamanlı giriş sayısını kaydeden bir sarmalayıcıdan geçer; giriş yine gerçek Chromium ile gerçek portala yapılır.
 */
@Import(SingleActiveConsumerIntegrationTest.RecordingPortalConfiguration.class)
@TestPropertySource(properties = "test.rpa-instance=A")
class SingleActiveConsumerIntegrationTest extends RpaIntegrationTest {

    private static final String QUEUE = "rpa.post-to-portal.q";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    record Entry(String instance, String invoiceNo) {
    }

    static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
    /** O an portala girilmekte olan faturalar (iki örnek toplamı). */
    static final Set<String> IN_FLIGHT_INVOICES = ConcurrentHashMap.newKeySet();
    /** Aynı anda ikinci kez girilmeye başlanan faturalar; boş kalmalı. */
    static final List<String> CONCURRENT_DUPLICATES = new CopyOnWriteArrayList<>();

    /** Gerçek girişi yapar; önce kimin girdiğini ve aynı anda kaç girişin sürdüğünü (iki örnek toplamı) kaydeder. */
    static class RecordingPortal extends PlaywrightPortal {

        private final String instance;

        RecordingPortal(RpaProperties properties, String instance) {
            super(properties);
            this.instance = instance;
        }

        @Override
        public Outcome submit(PortalValues invoice) {
            MAX_IN_FLIGHT.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
            if (!IN_FLIGHT_INVOICES.add(invoice.invoiceNo())) {
                CONCURRENT_DUPLICATES.add(invoice.invoiceNo());
            }
            ENTRIES.add(new Entry(instance, invoice.invoiceNo()));
            try {
                return super.submit(invoice);
            } finally {
                IN_FLIGHT_INVOICES.remove(invoice.invoiceNo());
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPortalConfiguration {

        @Bean
        @Primary
        RecordingPortal recordingPortal(RpaProperties properties, Environment environment) {
            return new RecordingPortal(properties, environment.getRequiredProperty("test.rpa-instance"));
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Environment environment;
    @Autowired
    private SimpleMessageListenerContainer postToPortalContainer;

    private ConfigurableApplicationContext instanceB;

    @AfterEach
    void closeSecondInstance() {
        if (instanceB != null) {
            instanceB.close();
        }
    }

    @Test
    void onlyOneInstanceConsumesAndStandbyTakesOverWhenActiveStops() {
        ENTRIES.clear();
        MAX_IN_FLIGHT.set(0);
        CONCURRENT_DUPLICATES.clear();
        instanceB = startSecondInstance();
        // İki consumer da kayıtlı; broker'a göre biri aktif, diğeri beklemede.
        await().atMost(TIMEOUT).until(() -> consumerStates().equals(List.of("single_active", "waiting")));

        // 1. aşama: hepsini ilk bağlanan örnek (A) işler, B dokunmaz.
        List<String> first = sendInvoices("SAC1", 3);
        await().atMost(TIMEOUT).until(() -> completed(first) == first.size());
        assertThat(ENTRIES).extracting(Entry::instance).containsOnly("A");
        assertThat(MAX_IN_FLIGHT.get()).as("devirden önce tek bot").isEqualTo(1);

        // 2. aşama: A ilk faturaya başladıktan sonra durur; kalanları yedek B devralır.
        List<String> second = sendInvoices("SAC2", 3);
        await().atMost(TIMEOUT).until(() -> ENTRIES.stream().anyMatch(e -> second.contains(e.invoiceNo())));
        postToPortalContainer.stop();
        await().atMost(TIMEOUT).until(() -> completed(second) == second.size());

        Map<String, List<String>> byInstance = ENTRIES.stream().filter(e -> second.contains(e.invoiceNo()))
                .collect(Collectors.groupingBy(Entry::instance,
                        Collectors.mapping(Entry::invoiceNo, Collectors.toList())));
        assertThat(byInstance.get("A")).isNotEmpty();
        assertThat(byInstance.get("B")).isNotEmpty();

        // Hiçbir fatura aynı anda iki kez işlenmedi; her fatura portala bir kez girildi.
        assertThat(CONCURRENT_DUPLICATES).isEmpty();
        assertThat(ENTRIES).extracting(Entry::invoiceNo).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(Stream.concat(first.stream(), second.stream()).toList());
        Stream.concat(first.stream(), second.stream())
                .forEach(invoiceNo -> assertThat(MockPortal.invoices(invoiceNo)).as(invoiceNo).hasSize(1));
        // A'nın consumer'ı gitti, B tek ve aktif.
        await().atMost(TIMEOUT).until(() -> consumerStates().equals(List.of("single_active")));
    }

    /**
     * Aynı rpa_db, broker ve portal; web sunucusu rastgele portta (A'nınki MOCK, port çakışmaz). Ayarlar
     * {@code run(args)} ile verilir: builder'ın {@code properties()}'i varsayılan özelliktir, application.yml'daki
     * {@code ${PORTAL_PASSWORD:}} gibi boş değerler onu ezerdi. Argümanlar JVM içinde kalır, süreç argümanı değildir.
     */
    private ConfigurableApplicationContext startSecondInstance() {
        List<String> args = new ArrayList<>(List.of("--test.rpa-instance=B", "--server.port=0",
                "--spring.main.banner-mode=off"));
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "spring.rabbitmq.host", "spring.rabbitmq.port", "spring.rabbitmq.username",
                "spring.rabbitmq.password", "spring.rabbitmq.virtual-host", "spring.data.redis.host",
                "spring.data.redis.port", "invoice.rpa.portal.url",
                "invoice.rpa.portal.username", "invoice.rpa.portal.password")) {
            args.add("--" + key + "=" + Objects.requireNonNull(environment.getProperty(key), key));
        }
        return new SpringApplicationBuilder(RpaServiceApplication.class, RecordingPortalConfiguration.class)
                .run(args.toArray(String[]::new));
    }

    private List<String> sendInvoices(String prefix, int count) {
        List<String> invoiceNos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID documentId = UUID.randomUUID();
            String invoiceNo = prefix + "-" + i + "-" + UUID.randomUUID();
            invoiceNos.add(invoiceNo);
            PostToPortal command = new PostToPortal(documentId, "4810293756", "Anadolu Rulman A.Ş.", invoiceNo,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"),
                    new BigDecimal("850.00"), "TRY");
            rabbitTemplate.send("invoice.commands", "rpa.post", converter.toAmqpMessage(
                    MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.POST_TO_PORTAL), command));
        }
        return invoiceNos;
    }

    private long completed(List<String> invoiceNos) {
        return jdbc.sql("SELECT count(*) FROM portal_submissions WHERE status = 'SUBMITTED' AND invoice_no IN (:nos)")
                .param("nos", invoiceNos).query(Long.class).single();
    }

    /**
     * Kuyruğun consumer'larının broker'daki durumu ({@code single_active} / {@code waiting}), sıralı. AMQP'nin
     * consumer sayısı burada kullanılmaz: SAC'li kuyrukta yalnızca aktif consumer'ı sayabilir.
     */
    private List<String> consumerStates() {
        return InfrastructureContainers.rabbitmqctl("list_consumers", "-p", InfrastructureContainers.RABBITMQ_VHOST,
                        "queue_name", "activity_status").lines()
                .map(line -> line.split("\t"))
                .filter(columns -> columns.length == 2 && columns[0].equals(QUEUE))
                .map(columns -> columns[1].strip())
                .sorted()
                .toList();
    }
}
