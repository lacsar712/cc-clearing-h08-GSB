package com.clearing.netting.adapter.out.persistence;

import com.clearing.netting.adapter.out.persistence.entity.ObligationJpaEntity;
import com.clearing.netting.adapter.out.persistence.repo.ObligationJpaRepository;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: findOpenBySettleDateAndCurrency must select only OPEN obligations.
 * A dataset containing NETTED/SETTLED (and CANCELLED) rows for the same
 * settleDate/currency must not leak terminal-state obligations into a netting run.
 */
@DataJpaTest
class ObligationRepositoryAdapterTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 10);

    @Autowired
    private ObligationJpaRepository jpaRepository;

    @Test
    void selectsOnlyOpenAmongTerminalStates() {
        ObligationRepositoryAdapter adapter = new ObligationRepositoryAdapter(jpaRepository);

        ObligationJpaEntity open = persist(ObligationStatus.OPEN, "USD", SETTLE_DATE, "run-old");
        persist(ObligationStatus.NETTED, "USD", SETTLE_DATE, "run-old");
        persist(ObligationStatus.SETTLED, "USD", SETTLE_DATE, "run-old");
        persist(ObligationStatus.CANCELLED, "USD", SETTLE_DATE, null);

        List<TradeObligation> found = adapter.findOpenBySettleDateAndCurrency(SETTLE_DATE, "USD");

        assertEquals(1, found.size());
        assertEquals(open.getObligationId(), found.get(0).getObligationId());
        assertEquals(ObligationStatus.OPEN, found.get(0).getStatus());
    }

    @Test
    void excludesOtherSettleDatesAndCurrencies() {
        ObligationRepositoryAdapter adapter = new ObligationRepositoryAdapter(jpaRepository);

        ObligationJpaEntity match = persist(ObligationStatus.OPEN, "USD", SETTLE_DATE, null);
        persist(ObligationStatus.OPEN, "EUR", SETTLE_DATE, null);
        persist(ObligationStatus.OPEN, "USD", SETTLE_DATE.plusDays(1), null);

        List<TradeObligation> found = adapter.findOpenBySettleDateAndCurrency(SETTLE_DATE, "USD");

        assertEquals(1, found.size());
        assertEquals(match.getObligationId(), found.get(0).getObligationId());
    }

    @Test
    void currencyMatchIsCaseInsensitive() {
        ObligationRepositoryAdapter adapter = new ObligationRepositoryAdapter(jpaRepository);

        ObligationJpaEntity open = persist(ObligationStatus.OPEN, "USD", SETTLE_DATE, null);

        List<TradeObligation> found = adapter.findOpenBySettleDateAndCurrency(SETTLE_DATE, "usd");

        assertEquals(1, found.size());
        assertEquals(open.getObligationId(), found.get(0).getObligationId());
    }

    @Test
    void returnsEmptyWhenNothingOpen() {
        ObligationRepositoryAdapter adapter = new ObligationRepositoryAdapter(jpaRepository);

        persist(ObligationStatus.NETTED, "USD", SETTLE_DATE, "run-old");
        persist(ObligationStatus.SETTLED, "USD", SETTLE_DATE, "run-old");

        List<TradeObligation> found = adapter.findOpenBySettleDateAndCurrency(SETTLE_DATE, "USD");

        assertTrue(found.isEmpty());
    }

    private ObligationJpaEntity persist(ObligationStatus status, String currency, LocalDate settleDate, String runId) {
        ObligationJpaEntity e = new ObligationJpaEntity();
        e.setObligationId(UUID.randomUUID().toString());
        e.setPayerMemberId("A");
        e.setPayeeMemberId("B");
        e.setCurrency(currency);
        e.setAmount(new BigDecimal("100.00000000"));
        e.setTradeDate(settleDate.minusDays(1));
        e.setSettleDate(settleDate);
        e.setStatus(status);
        e.setNettingRunId(runId);
        return jpaRepository.save(e);
    }
}
