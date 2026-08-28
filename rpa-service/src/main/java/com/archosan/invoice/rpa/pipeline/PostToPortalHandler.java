package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.Exchanges;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.consumer.InboxCompletion;
import com.archosan.invoice.messaging.consumer.LongRunningMessageHandler;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import com.archosan.invoice.rpa.RpaProperties;
import com.archosan.invoice.rpa.lock.InvoiceLocks;
import com.archosan.invoice.rpa.portal.PlaywrightPortal;
import com.archosan.invoice.rpa.portal.PortalAttemptFailedException;
import com.archosan.invoice.rpa.portal.PortalErrorException;
import com.archosan.invoice.rpa.portal.PortalRejectedException;
import com.archosan.invoice.rpa.portal.PortalValues;
import com.archosan.invoice.rpa.submission.PortalAttempts;
import com.archosan.invoice.rpa.submission.PortalSubmissions;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code PostToPortal} (DECISIONS.md §4.4; uzun iş, B-06):
 *
 * <ol>
 *   <li>Değerler portal biçimine çevrilir; çevrilemiyorsa kalıcı hata → DLQ, portala dokunulmaz.</li>
 *   <li>Fatura kilidi alınır (B-34, FR-R7). Alınamazsa (başka örnekte ya da Redis yok: fail-closed) mesaj aynı kimlikle
 *       bekleme odasına ({@code invoice.retry} · {@code rpa.post.wait}, 30 sn) yazılır, inbox'a yazılmadan ack edilir;
 *       teslim limiti tüketilmez, portala dokunulmaz. Kilit iş bitip sonuç yazılınca bırakılır.</li>
 *   <li>{@code portal_submissions} kendi transaction'ında {@code IN_PROGRESS} olur (deneme sayısı artar). Belge zaten
 *       {@code SUBMITTED} ise portala girilmez, mevcut kayıt numarası yeniden bildirilir.</li>
 *   <li>Playwright ile login, ön arama (B-35), form, kayıt numarası (transaction dışında). Ön arama kaydı bulursa form
 *       doldurulmaz.</li>
 *   <li>{@code SUBMITTED} ya da {@code FOUND_EXISTING} + inbox + {@code RpaCompleted(foundExisting)} tek
 *       transaction'da, sonra ack.</li>
 * </ol>
 * Başarısız her denemenin izi ve ekran görüntüsü {@code rpa_attempts}'a yazılır (B-37, FR-R6). Portalın form
 * doğrulaması kalıcıdır → DLQ. Hata sayfası, düşen oturum, zaman aşımı ve bulunamayan eleman geçicidir: mesaj bekleme
 * odasında 30 sn bekleyip yeniden denenir; {@code invoice.rpa.max-attempts} dolunca DLQ → document-service
 * {@code RPA_FAILED} (FR-R5). Her denemede ön arama da yapıldığı için portal kaydedip hata dönmüşse bile çift kayıt
 * oluşmaz.
 */
@Component
class PostToPortalHandler implements LongRunningMessageHandler<PostToPortal> {

    private static final Logger log = LoggerFactory.getLogger(PostToPortalHandler.class);

    /** Bekleme odasının routing key'i ({@code invoice.retry} → {@code rpa.post-to-portal.wait-30s}). */
    static final String WAIT_ROUTING_KEY = "rpa.post.wait";

    private final PortalSubmissions submissions;
    private final PlaywrightPortal portal;
    private final OutboxWriter outbox;
    private final InvoiceLocks locks;
    private final PortalAttempts attempts;
    private final int maxAttempts;

    PostToPortalHandler(PortalSubmissions submissions, PlaywrightPortal portal, OutboxWriter outbox,
            InvoiceLocks locks, PortalAttempts attempts, RpaProperties properties) {
        this.submissions = submissions;
        this.portal = portal;
        this.outbox = outbox;
        this.locks = locks;
        this.attempts = attempts;
        this.maxAttempts = properties.maxAttempts();
    }

    @Override
    public void handle(MessageEnvelope envelope, PostToPortal command, InboxCompletion completion) {
        UUID documentId = command.documentId();
        PortalValues values;
        try {
            values = PortalValues.of(command);
        } catch (PortalRejectedException e) {
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }

        Optional<InvoiceLocks.Held> lock = locks.tryAcquire(values.supplierVkn(), values.invoiceNo());
        if (lock.isEmpty()) {
            completion.defer(() -> outbox.republish(envelope, command, Exchanges.RETRY, WAIT_ROUTING_KEY));
            log.info("Fatura kilidi alınamadı, bekleme odasına: invoiceNo={}", values.invoiceNo());
            return;
        }
        try (InvoiceLocks.Held _ = lock.get()) {
            enter(envelope, command, values, completion);
        }
    }

    private void enter(MessageEnvelope envelope, PostToPortal command, PortalValues values,
            InboxCompletion completion) {
        UUID documentId = command.documentId();
        switch (submissions.begin(documentId, envelope.messageId(), values.supplierVkn(), values.invoiceNo())) {
            case PortalSubmissions.AlreadySubmitted done -> {
                log.info("Fatura zaten girilmiş, kayıt no yeniden bildiriliyor: refNo={}", done.portalRefNo());
                completion.complete(() -> outbox.add(documentId,
                        new RpaCompleted(documentId, done.portalRefNo(), done.foundExisting())));
            }
            case PortalSubmissions.Started started -> {
                log.info("Portala giriliyor: invoiceNo={}, deneme={}", values.invoiceNo(), started.attempt());
                PlaywrightPortal.Outcome outcome;
                try {
                    outcome = portal.submit(values);
                } catch (RuntimeException e) {
                    failed(envelope, command, started, e, completion);
                    return;
                }
                String refNo = outcome.portalRefNo();
                boolean found = outcome instanceof PlaywrightPortal.FoundExisting;
                completion.complete(() -> {
                    if (found) {
                        submissions.markFoundExisting(documentId, refNo);
                    } else {
                        submissions.markSubmitted(documentId, refNo);
                    }
                    outbox.add(documentId, new RpaCompleted(documentId, refNo, found));
                });
                if (found) {
                    log.warn("Fatura portalda zaten vardı (ön arama), yeniden girilmedi: invoiceNo={}, refNo={}, "
                                    + "deneme={}",
                            values.invoiceNo(), refNo, started.attempt());
                } else {
                    log.info("Portala girildi: invoiceNo={}, refNo={}", values.invoiceNo(), refNo);
                }
            }
        }
    }

    /**
     * Başarısız deneme (B-37): iz ve ekran görüntüsü kaydedilir (FR-R6). Kalıcı hata (form doğrulaması) → DLQ. Geçici
     * hata (hata sayfası, düşen oturum, zaman aşımı, eleman yok) → deneme sınırı dolmadıysa bekleme odası (30 sn, aynı
     * kimlik, teslim limiti tüketilmez), dolduysa DLQ → {@code RPA_FAILED} (FR-R5). Beklenmeyen hata (programlama
     * hatası) eskisi gibi geri konur, teslim limitinden sonra DLQ. Sınır tura uygulanır: yönetici yeniden işletince
     * (yeni komut) yeni tur kendi hakkıyla başlar (B-38).
     */
    private void failed(MessageEnvelope envelope, PostToPortal command, PortalSubmissions.Started started,
            RuntimeException error, InboxCompletion completion) {
        int attempt = started.roundAttempt();
        PortalAttemptFailedException failure = error instanceof PortalAttemptFailedException f
                ? f : new PortalAttemptFailedException("tarayıcı", null, error);
        attempts.record(command.documentId(), started.attempt(), failure);
        RuntimeException cause = failure.getCause();
        if (cause instanceof PortalRejectedException) {
            throw new AmqpRejectAndDontRequeueException(failure.getMessage(), failure);
        }
        if (!(cause instanceof PortalErrorException || cause instanceof PlaywrightException)) {
            throw failure;
        }
        if (attempt >= maxAttempts) {
            throw new AmqpRejectAndDontRequeueException(
                    "Portal denemeleri tükendi (" + attempt + "/" + maxAttempts + "): " + failure.getMessage(),
                    failure);
        }
        completion.defer(() -> outbox.republish(envelope, command, Exchanges.RETRY, WAIT_ROUTING_KEY));
        log.warn("Portal denemesi {}/{} başarısız, bekleme odasına: {}", attempt, maxAttempts, failure.getMessage());
    }
}
