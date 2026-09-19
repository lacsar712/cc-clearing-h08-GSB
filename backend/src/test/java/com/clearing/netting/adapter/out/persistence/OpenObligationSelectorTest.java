package com.clearing.netting.adapter.out.persistence;

import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenObligationSelectorTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 10);

    @Test
    void keepsOnlyOpenObligations() {
        TradeObligation open = obligation("o-open", ObligationStatus.OPEN, null);
        TradeObligation netted = obligation("o-netted", ObligationStatus.NETTED, "run-1");
        TradeObligation settled = obligation("o-settled", ObligationStatus.SETTLED, "run-1");
        TradeObligation cancelled = obligation("o-cancelled", ObligationStatus.CANCELLED, null);

        List<TradeObligation> picked = OpenObligationSelector.keepParticipating(
                List.of(open, netted, settled, cancelled));

        assertEquals(1, picked.size());
        assertEquals("o-open", picked.get(0).getObligationId());
    }

    @Test
    void terminalStatusesNeverLookOpen() {
        assertTrue(OpenObligationSelector.looksOpen(obligation("o", ObligationStatus.OPEN, null)));
        assertFalse(OpenObligationSelector.looksOpen(obligation("o", ObligationStatus.NETTED, "run-1")));
        assertFalse(OpenObligationSelector.looksOpen(obligation("o", ObligationStatus.SETTLED, "run-1")));
        assertFalse(OpenObligationSelector.looksOpen(obligation("o", ObligationStatus.CANCELLED, null)));
        assertFalse(OpenObligationSelector.looksOpen(null));
    }

    @Test
    void nullAndEmptySourceYieldEmpty() {
        assertTrue(OpenObligationSelector.keepParticipating(null).isEmpty());
        assertTrue(OpenObligationSelector.keepParticipating(List.of()).isEmpty());
    }

    private TradeObligation obligation(String id, ObligationStatus status, String runId) {
        return new TradeObligation(
                id, "A", "B", "USD", new BigDecimal("10"),
                D.minusDays(1), D, status, runId);
    }
}
