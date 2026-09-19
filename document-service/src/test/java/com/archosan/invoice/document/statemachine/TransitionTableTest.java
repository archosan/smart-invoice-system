package com.archosan.invoice.document.statemachine;

import com.archosan.invoice.document.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionTableTest {

    /** DECISIONS.md §4.1 tablosu, satır satır (v1 + v2). */
    private static final Set<String> SPECIFICATION = Set.of(
            "RECEIVED>EXTRACTED",
            "RECEIVED>NEEDS_REVIEW",
            "EXTRACTED>NEEDS_REVIEW",
            "EXTRACTED>DUPLICATE_SUSPECTED",
            "EXTRACTED>VALIDATED",
            "NEEDS_REVIEW>EXTRACTED",
            "NEEDS_REVIEW>REJECTED",
            "DUPLICATE_SUSPECTED>VALIDATED",
            "DUPLICATE_SUSPECTED>REJECTED",
            "VALIDATED>COMPLIANCE_CHECKED",
            "VALIDATED>PENDING_APPROVAL",
            "PENDING_APPROVAL>COMPLIANCE_CHECKED",
            "PENDING_APPROVAL>REJECTED",
            "COMPLIANCE_CHECKED>QUEUED_FOR_RPA",
            "QUEUED_FOR_RPA>POSTED",
            "QUEUED_FOR_RPA>RPA_FAILED",
            "RPA_FAILED>QUEUED_FOR_RPA");

    @Test
    void allowsExactlyTheSpecifiedTransitions() {
        for (DocumentStatus from : DocumentStatus.values()) {
            for (DocumentStatus to : DocumentStatus.values()) {
                assertThat(TransitionTable.isAllowed(from, to))
                        .as("%s → %s", from, to)
                        .isEqualTo(SPECIFICATION.contains(from + ">" + to));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"POSTED", "REJECTED"})
    void terminalStatesHaveNoOutgoingTransitions(DocumentStatus terminal) {
        assertThat(TransitionTable.targetsFrom(terminal)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(DocumentStatus.class)
    void noStateTransitionsToItself(DocumentStatus status) {
        assertThat(TransitionTable.isAllowed(status, status)).isFalse();
    }

    @Test
    void nothingLeadsBackToReceived() {
        for (DocumentStatus from : DocumentStatus.values()) {
            assertThat(TransitionTable.isAllowed(from, DocumentStatus.RECEIVED)).as("%s → RECEIVED", from).isFalse();
        }
    }
}
