package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a second netting run for the same settleDate/currency must only
 * pick OPEN obligations. NETTED/SETTLED obligations from the first run must
 * never re-enter netting and their status must stay stable.
 * Each test uses its own settleDate because the port exposes no delete.
 */
@SpringBootTest
class SameDayCurrencyReNettingIntegrationTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 10);
    private static final String CCY = "USD";

    @Autowired
    private NettingApplicationService nettingService;

    @Autowired
    private ObligationRepositoryPort obligationRepository;

    @Autowired
    private MemberRepositoryPort memberRepository;

    @BeforeEach
    void setUp() {
        memberRepository.save(new Member("A", "Bank A", MemberStatus.ACTIVE));
        memberRepository.save(new Member("B", "Bank B", MemberStatus.ACTIVE));
        memberRepository.save(new Member("C", "Bank C", MemberStatus.ACTIVE));
    }

    @Test
    void findOpenIgnoresNettedSettledCancelledDataset() {
        LocalDate settle = BASE_DATE;
        obligationRepository.save(obligation("sel-open", "A", "B", "100", settle, ObligationStatus.OPEN, null));
        obligationRepository.save(obligation("sel-netted", "B", "C", "60", settle, ObligationStatus.NETTED, "old-run"));
        obligationRepository.save(obligation("sel-settled", "C", "A", "40", settle, ObligationStatus.SETTLED, "old-run"));
        obligationRepository.save(obligation("sel-cancelled", "A", "C", "10", settle, ObligationStatus.CANCELLED, null));

        List<TradeObligation> picked =
                obligationRepository.findOpenBySettleDateAndCurrency(settle, CCY);

        List<String> ids = picked.stream().map(TradeObligation::getObligationId).toList();
        assertEquals(1, ids.size());
        assertTrue(ids.contains("sel-open"));
        assertTrue(picked.stream().allMatch(o -> o.getStatus() == ObligationStatus.OPEN));
    }

    @Test
    void secondRunAfterSettleOnlyNetsNewOpenObligations() {
        LocalDate settle = BASE_DATE.plusDays(1);
        // First batch: A->B 100, B->C 60. Both get netted then settled.
        obligationRepository.save(obligation("first-1", "A", "B", "100", settle, ObligationStatus.OPEN, null));
        obligationRepository.save(obligation("first-2", "B", "C", "60", settle, ObligationStatus.OPEN, null));

        NettingApplicationService.NettingRunResult run1 = nettingService.execute(settle, CCY);
        assertEquals(NettingRunStatus.COMPLETED, run1.run().getStatus());
        nettingService.settle(run1.run().getRunId());

        // A new OPEN obligation arrives for the same settleDate/currency,
        // alongside the now-SETTLED obligations.
        obligationRepository.save(obligation("second-1", "C", "A", "30", settle, ObligationStatus.OPEN, null));

        NettingApplicationService.NettingRunResult run2 = nettingService.execute(settle, CCY);
        assertEquals(NettingRunStatus.COMPLETED, run2.run().getStatus());

        // Second run participation list contains only the new OPEN obligation.
        List<String> run2Ids = run2.obligations().stream().map(TradeObligation::getObligationId).toList();
        assertEquals(1, run2Ids.size());
        assertTrue(run2Ids.contains("second-1"));

        // Terminal obligations from run 1 keep their status and original run link.
        Map<String, TradeObligation> byId = obligationRepository.findByFilters(CCY, settle, null).stream()
                .collect(Collectors.toMap(TradeObligation::getObligationId, Function.identity()));
        assertTerminal(byId.get("first-1"), ObligationStatus.SETTLED, run1.run().getRunId());
        assertTerminal(byId.get("first-2"), ObligationStatus.SETTLED, run1.run().getRunId());
        assertTerminal(byId.get("second-1"), ObligationStatus.NETTED, run2.run().getRunId());

        // Batch detail of the old run must still surface both obligations in SETTLED.
        List<TradeObligation> run1Detail = nettingService.getRunObligations(run1.run().getRunId());
        assertEquals(2, run1Detail.size());
        assertTrue(run1Detail.stream().allMatch(o -> o.getStatus() == ObligationStatus.SETTLED));
        assertTrue(run1Detail.stream().allMatch(o -> o.getNettingRunId().equals(run1.run().getRunId())));
    }

    @Test
    void secondRunWithNoOpenLeftFailsAndLeavesTerminalRowsUntouched() {
        LocalDate settle = BASE_DATE.plusDays(2);
        obligationRepository.save(obligation("only-1", "A", "B", "100", settle, ObligationStatus.OPEN, null));
        NettingApplicationService.NettingRunResult run1 = nettingService.execute(settle, CCY);
        nettingService.settle(run1.run().getRunId());

        // No OPEN obligations remain: the second run must fail rather than
        // pulling the SETTLED obligation back in.
        DomainException ex = assertThrows(DomainException.class, () -> nettingService.execute(settle, CCY));
        assertEquals("NO_OBLIGATIONS", ex.getCode());

        TradeObligation reloaded = obligationRepository.findById("only-1").orElseThrow();
        assertTerminal(reloaded, ObligationStatus.SETTLED, run1.run().getRunId());
    }

    private void assertTerminal(TradeObligation o, ObligationStatus status, String runId) {
        assertEquals(status, o.getStatus(), "obligation " + o.getObligationId() + " status changed");
        assertEquals(runId, o.getNettingRunId());
    }

    private TradeObligation obligation(String id, String payer, String payee, String amount,
                                       LocalDate settle, ObligationStatus status, String runId) {
        return new TradeObligation(
                id, payer, payee, CCY, new BigDecimal(amount),
                settle.minusDays(1), settle, status, runId);
    }
}
