package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a second netting run for the same settleDate/currency must only
 * pull OPEN obligations. Obligations already NETTED/SETTLED by an earlier run
 * must neither re-enter the participation list nor change status.
 */
class NettingApplicationServiceTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 10);

    private InMemoryObligationRepository obligations;
    private InMemoryRunRepository runs;
    private InMemoryPositionRepository positions;
    private NettingApplicationService service;

    @BeforeEach
    void setUp() {
        obligations = new InMemoryObligationRepository();
        runs = new InMemoryRunRepository();
        positions = new InMemoryPositionRepository();
        InMemoryMemberRepository members = new InMemoryMemberRepository();
        members.save(new Member("A", "Bank A", MemberStatus.ACTIVE));
        members.save(new Member("B", "Bank B", MemberStatus.ACTIVE));
        service = new NettingApplicationService(
                runs, obligations, members, positions, new NettingRunStatusService(runs));
    }

    @Test
    void secondRunAfterSettleLeavesSettledObligationsUntouched() {
        TradeObligation o1 = obligations.save(open("A", "B", "100"));
        TradeObligation o2 = obligations.save(open("B", "A", "40"));

        NettingRun run1 = service.execute(SETTLE_DATE, "USD").run();
        assertEquals(NettingRunStatus.COMPLETED, run1.getStatus());
        service.settle(run1.getRunId());
        assertEquals(ObligationStatus.SETTLED, obligations.findById(o1.getObligationId()).orElseThrow().getStatus());
        assertEquals(ObligationStatus.SETTLED, obligations.findById(o2.getObligationId()).orElseThrow().getStatus());

        // Second run for the same settleDate/currency: nothing OPEN remains.
        DomainException ex = assertThrows(DomainException.class, () -> service.execute(SETTLE_DATE, "USD"));
        assertEquals("NO_OBLIGATIONS", ex.getCode());

        // Settled obligations keep their status and their original run link.
        TradeObligation after1 = obligations.findById(o1.getObligationId()).orElseThrow();
        TradeObligation after2 = obligations.findById(o2.getObligationId()).orElseThrow();
        assertEquals(ObligationStatus.SETTLED, after1.getStatus());
        assertEquals(ObligationStatus.SETTLED, after2.getStatus());
        assertEquals(run1.getRunId(), after1.getNettingRunId());
        assertEquals(run1.getRunId(), after2.getNettingRunId());

        // The failed second run must not list the old terminal-state obligations as participants.
        NettingRun failedRun = runs.findAllOrderByCreatedAtDesc().stream()
                .filter(r -> !r.getRunId().equals(run1.getRunId()))
                .findFirst().orElseThrow();
        assertEquals(NettingRunStatus.FAILED, failedRun.getStatus());
        assertTrue(obligations.findByNettingRunId(failedRun.getRunId()).isEmpty());

        // The first run's participation list is intact.
        assertEquals(2, obligations.findByNettingRunId(run1.getRunId()).size());
    }

    @Test
    void secondRunNetsOnlyRemainingOpenObligations() {
        TradeObligation o1 = obligations.save(open("A", "B", "100"));
        TradeObligation o2 = obligations.save(open("B", "A", "40"));

        NettingRun run1 = service.execute(SETTLE_DATE, "USD").run();
        service.settle(run1.getRunId());

        TradeObligation o3 = obligations.save(open("A", "B", "30"));
        NettingApplicationService.NettingRunResult second = service.execute(SETTLE_DATE, "USD");

        assertEquals(NettingRunStatus.COMPLETED, second.run().getStatus());

        // Participation list of the second run: only the still-OPEN obligation.
        assertEquals(1, second.obligations().size());
        assertEquals(o3.getObligationId(), second.obligations().get(0).getObligationId());
        assertEquals(1, obligations.findByNettingRunId(second.run().getRunId()).size());

        // The newly netted obligation points at the second run.
        TradeObligation after3 = obligations.findById(o3.getObligationId()).orElseThrow();
        assertEquals(ObligationStatus.NETTED, after3.getStatus());
        assertEquals(second.run().getRunId(), after3.getNettingRunId());

        // Old SETTLED obligations are untouched.
        TradeObligation after1 = obligations.findById(o1.getObligationId()).orElseThrow();
        TradeObligation after2 = obligations.findById(o2.getObligationId()).orElseThrow();
        assertEquals(ObligationStatus.SETTLED, after1.getStatus());
        assertEquals(ObligationStatus.SETTLED, after2.getStatus());
        assertEquals(run1.getRunId(), after1.getNettingRunId());
        assertEquals(run1.getRunId(), after2.getNettingRunId());

        // Positions of the second run reflect only the remaining OPEN obligation.
        Map<String, BigDecimal> byMember = positions.findByRunId(second.run().getRunId()).stream()
                .collect(Collectors.toMap(NetPosition::getMemberId, NetPosition::getNetAmount));
        assertEquals(0, byMember.get("A").compareTo(new BigDecimal("-30.00000000")));
        assertEquals(0, byMember.get("B").compareTo(new BigDecimal("30.00000000")));
    }

    private TradeObligation open(String payer, String payee, String amount) {
        return TradeObligation.open(
                payer, payee, "USD", new BigDecimal(amount),
                SETTLE_DATE.minusDays(1), SETTLE_DATE);
    }

    private static final class InMemoryObligationRepository implements ObligationRepositoryPort {
        private final Map<String, TradeObligation> store = new LinkedHashMap<>();

        @Override
        public TradeObligation save(TradeObligation obligation) {
            store.put(obligation.getObligationId(), obligation);
            return obligation;
        }

        @Override
        public List<TradeObligation> saveAll(List<TradeObligation> batch) {
            batch.forEach(this::save);
            return batch;
        }

        @Override
        public Optional<TradeObligation> findById(String obligationId) {
            return Optional.ofNullable(store.get(obligationId));
        }

        @Override
        public List<TradeObligation> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<TradeObligation> findByFilters(String currency, LocalDate settleDate, ObligationStatus status) {
            return store.values().stream()
                    .filter(o -> currency == null || o.getCurrency().equalsIgnoreCase(currency))
                    .filter(o -> settleDate == null || o.getSettleDate().equals(settleDate))
                    .filter(o -> status == null || o.getStatus() == status)
                    .collect(Collectors.toList());
        }

        @Override
        public List<TradeObligation> findOpenBySettleDateAndCurrency(LocalDate settleDate, String currency) {
            // Mirrors the port contract: only OPEN obligations are eligible for a netting run.
            return findByFilters(currency, settleDate, ObligationStatus.OPEN);
        }

        @Override
        public List<TradeObligation> findByNettingRunId(String runId) {
            return store.values().stream()
                    .filter(o -> runId.equals(o.getNettingRunId()))
                    .collect(Collectors.toList());
        }
    }

    private static final class InMemoryRunRepository implements NettingRunRepositoryPort {
        private final Map<String, NettingRun> store = new LinkedHashMap<>();

        @Override
        public NettingRun save(NettingRun run) {
            store.put(run.getRunId(), run);
            return run;
        }

        @Override
        public Optional<NettingRun> findById(String runId) {
            return Optional.ofNullable(store.get(runId));
        }

        @Override
        public List<NettingRun> findAllOrderByCreatedAtDesc() {
            return new ArrayList<>(store.values());
        }
    }

    private static final class InMemoryMemberRepository implements MemberRepositoryPort {
        private final Map<String, Member> store = new LinkedHashMap<>();

        @Override
        public Member save(Member member) {
            store.put(member.getMemberId(), member);
            return member;
        }

        @Override
        public Optional<Member> findById(String memberId) {
            return Optional.ofNullable(store.get(memberId));
        }

        @Override
        public List<Member> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<Member> findByIds(Iterable<String> memberIds) {
            List<Member> out = new ArrayList<>();
            for (String id : memberIds) {
                findById(id).ifPresent(out::add);
            }
            return out;
        }
    }

    private static final class InMemoryPositionRepository implements NetPositionRepositoryPort {
        private final List<NetPosition> store = new ArrayList<>();

        @Override
        public List<NetPosition> saveAll(List<NetPosition> batch) {
            store.addAll(batch);
            return batch;
        }

        @Override
        public List<NetPosition> findByRunId(String runId) {
            return store.stream().filter(p -> p.getRunId().equals(runId)).collect(Collectors.toList());
        }
    }
}
