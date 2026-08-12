package com.archosan.invoice.document.statemachine;

import com.archosan.invoice.document.DocumentStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.archosan.invoice.document.DocumentStatus.COMPLIANCE_CHECKED;
import static com.archosan.invoice.document.DocumentStatus.DUPLICATE_SUSPECTED;
import static com.archosan.invoice.document.DocumentStatus.EXTRACTED;
import static com.archosan.invoice.document.DocumentStatus.NEEDS_REVIEW;
import static com.archosan.invoice.document.DocumentStatus.PENDING_APPROVAL;
import static com.archosan.invoice.document.DocumentStatus.POSTED;
import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;
import static com.archosan.invoice.document.DocumentStatus.RECEIVED;
import static com.archosan.invoice.document.DocumentStatus.REJECTED;
import static com.archosan.invoice.document.DocumentStatus.RPA_FAILED;
import static com.archosan.invoice.document.DocumentStatus.VALIDATED;

/**
 * İzin verilen durum geçişleri (FR-D4; DECISIONS.md §4.1 tablosu, v1 ve v2 satırları). Tabloda olmayan her geçiş
 * reddedilir; {@code POSTED} ve {@code REJECTED} son durumlardır. v2 satırları burada yalnızca veridir, onları
 * tetikleyen kod v2'de gelir.
 */
public final class TransitionTable {

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED = build();

    private TransitionTable() {
    }

    public static boolean isAllowed(DocumentStatus from, DocumentStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** {@code from} durumundan gidilebilecek durumlar. */
    public static Set<DocumentStatus> targetsFrom(DocumentStatus from) {
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }

    private static Map<DocumentStatus, Set<DocumentStatus>> build() {
        Map<DocumentStatus, Set<DocumentStatus>> allowed = new EnumMap<>(DocumentStatus.class);
        for (DocumentStatus status : DocumentStatus.values()) {
            allowed.put(status, EnumSet.noneOf(DocumentStatus.class));
        }
        allow(allowed, RECEIVED, EXTRACTED, NEEDS_REVIEW);
        allow(allowed, EXTRACTED, NEEDS_REVIEW, DUPLICATE_SUSPECTED, VALIDATED);
        allow(allowed, NEEDS_REVIEW, EXTRACTED, REJECTED);                  // v2: uzman düzeltmesi / reddi
        allow(allowed, DUPLICATE_SUSPECTED, VALIDATED, REJECTED);           // v2: mükerrer kararı
        allow(allowed, VALIDATED, COMPLIANCE_CHECKED, PENDING_APPROVAL);
        allow(allowed, PENDING_APPROVAL, COMPLIANCE_CHECKED, REJECTED);     // v2: onaycı kararı
        allow(allowed, COMPLIANCE_CHECKED, QUEUED_FOR_RPA);
        allow(allowed, QUEUED_FOR_RPA, POSTED, RPA_FAILED);
        allow(allowed, RPA_FAILED, QUEUED_FOR_RPA);                         // v2: yönetici yeniden işletmesi
        return allowed;
    }

    private static void allow(Map<DocumentStatus, Set<DocumentStatus>> allowed, DocumentStatus from,
            DocumentStatus... targets) {
        Collections.addAll(allowed.get(from), targets);
    }
}
