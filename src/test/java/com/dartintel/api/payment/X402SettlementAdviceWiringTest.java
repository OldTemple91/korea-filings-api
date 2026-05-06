package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.dartintel.api.payment.dto.PaymentRequirement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring test for the H2 gap surfaced in the round-10 review:
 * verifies that {@link X402SettlementAdvice#persistPaymentLog} actually
 * routes the right outcome to {@link PaymentNotifier} AND
 * {@link PaymentLogReconciliationMonitor} on each of the three
 * persistence outcomes — duplicate (idempotent), non-duplicate
 * integrity violation (the round-7 silent-drop class), and database
 * outage. A future refactor that swallows the
 * {@code reconciliationFailure} flag or forgets to call
 * {@code recordReconciliationFailure()} on either non-duplicate
 * branch would make the production gauge / counter stop firing in
 * silence; this test guards that wiring.
 *
 * <p>Constructed with all-mocked deps and the package-private
 * {@code persistPaymentLog} entry point, so the test runs in
 * milliseconds without {@code @SpringBootTest} or Postgres.
 */
class X402SettlementAdviceWiringTest {

    private FacilitatorClient facilitatorClient;
    private PaymentLogRepository paymentLogRepository;
    private PaymentStore paymentStore;
    private ObjectMapper objectMapper;
    private PaymentNotifier paymentNotifier;
    private PaymentLogReconciliationMonitor reconciliationMonitor;
    private X402SettlementAdvice advice;

    @BeforeEach
    void setUp() {
        facilitatorClient = mock(FacilitatorClient.class);
        paymentLogRepository = mock(PaymentLogRepository.class);
        paymentStore = mock(PaymentStore.class);
        objectMapper = mock(ObjectMapper.class);
        paymentNotifier = mock(PaymentNotifier.class);
        reconciliationMonitor = mock(PaymentLogReconciliationMonitor.class);

        advice = new X402SettlementAdvice(
                facilitatorClient,
                paymentLogRepository,
                paymentStore,
                objectMapper,
                paymentNotifier,
                reconciliationMonitor
        );
    }

    @Test
    void successPath_doesNotIncrementReconciliationCounter() {
        when(paymentLogRepository.save(any(PaymentLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        advice.persistPaymentLog(verified(), settled());

        verify(paymentLogRepository, times(1)).save(any(PaymentLog.class));
        verify(reconciliationMonitor, never()).recordReconciliationFailure();
        verify(paymentNotifier).notifySettlement(any(), any(), any(), eqBoolean(false));
    }

    @Test
    void duplicateUniqueViolation_swallowsAsIdempotentNoCounterBump() {
        SQLException uniqueViolation = new SQLException("duplicate key", "23505");
        when(paymentLogRepository.save(any(PaymentLog.class)))
                .thenThrow(new DataIntegrityViolationException("dup", uniqueViolation));

        advice.persistPaymentLog(verified(), settled());

        // Idempotent path — no reconciliation counter bump, notifier
        // still called with reconciliationFailure=false because the
        // first row is already on disk.
        verify(reconciliationMonitor, never()).recordReconciliationFailure();
        verify(paymentNotifier).notifySettlement(any(), any(), any(), eqBoolean(false));
    }

    @Test
    void columnTooLong_22001_isCountedAndFlaggedForReconciliation() {
        // The exact failure mode the round-7 / round-9 silent-drop bug
        // produced. Asserts the new counter increments AND the
        // notifier sees reconciliationFailure=true.
        SQLException tooLong = new SQLException("value too long", "22001");
        when(paymentLogRepository.save(any(PaymentLog.class)))
                .thenThrow(new DataIntegrityViolationException("schema mismatch", tooLong));

        advice.persistPaymentLog(verified(), settled());

        verify(reconciliationMonitor, times(1)).recordReconciliationFailure();
        verify(paymentNotifier).notifySettlement(any(), any(), any(), eqBoolean(true));
    }

    @Test
    void notNullViolation_23502_isAlsoCountedAndFlagged() {
        SQLException notNull = new SQLException("null in column", "23502");
        when(paymentLogRepository.save(any(PaymentLog.class)))
                .thenThrow(new DataIntegrityViolationException("not null", notNull));

        advice.persistPaymentLog(verified(), settled());

        verify(reconciliationMonitor, times(1)).recordReconciliationFailure();
        verify(paymentNotifier).notifySettlement(any(), any(), any(), eqBoolean(true));
    }

    @Test
    void databaseUnreachable_isAlsoCountedAndFlagged() {
        // Different exception class but same operator outcome —
        // funds in flight, row missing, alert needed. Both branches
        // should bump the same counter so a Grafana alert fires
        // identically regardless of which sub-cause triggered it.
        when(paymentLogRepository.save(any(PaymentLog.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "postgres unreachable"));

        advice.persistPaymentLog(verified(), settled());

        verify(reconciliationMonitor, times(1)).recordReconciliationFailure();
        verify(paymentNotifier).notifySettlement(any(), any(), any(), eqBoolean(true));
    }

    // ------------------------------------------------------------------

    private static VerifiedPayment verified() {
        PaymentRequirement requirement = new PaymentRequirement(
                "exact",
                "eip155:8453",
                "5000",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                60,
                java.util.Map.of("name", "USD Coin", "version", "2")
        );
        return new VerifiedPayment(
                "nonce:0x" + "f".repeat(64),
                null, // payload not consulted by persistPaymentLog
                requirement,
                "0x254A42D7c617B38c7B43186e892d3af4bf9D6c44",
                "/v1/disclosures/summary?rcptNo=20260424900874"
        );
    }

    private static FacilitatorSettleResponse settled() {
        return new FacilitatorSettleResponse(
                true, null, "0xabc123", "eip155:8453",
                "0x254A42D7c617B38c7B43186e892d3af4bf9D6c44"
        );
    }

    /** Mockito boolean matcher for code clarity. */
    private static boolean eqBoolean(boolean expected) {
        return org.mockito.ArgumentMatchers.eq(expected);
    }
}
