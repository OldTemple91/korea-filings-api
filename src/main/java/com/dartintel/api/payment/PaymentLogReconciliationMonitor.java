package com.dartintel.api.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Surfaces the {@code SLO.md} "{@code payment_log} reconciliation gap
 * = 0 rows" target as a Prometheus-scrapable gauge, so any future
 * regression of the round-7 silent-drop class is visible in Grafana
 * within minutes — not detected by a manual SDK live test like the
 * 2026-05-06 incident was.
 *
 * <p>What this guards:
 *
 * <ul>
 *   <li><b>Reconciliation gap gauge</b> — periodically counts
 *       {@code payment_log} rows where {@code facilitator_tx_id IS NULL}
 *       AND {@code settled_at < NOW() - 5 minutes}. The 5-minute cutoff
 *       excludes rows mid-flight inside the settle transaction (the
 *       row is persisted with a non-null tx hash in a single
 *       transaction; a value above zero means the row was committed
 *       without the hash). The SLO target is exactly 0; non-zero on a
 *       live mainnet endpoint is a P0 incident.</li>
 *   <li><b>Reconciliation failure counter</b> — incremented by
 *       {@link X402SettlementAdvice} when {@code persistPaymentLog}
 *       takes the non-duplicate-integrity-violation branch (i.e.
 *       facilitator returned success but the row was rejected by the
 *       schema). Independent of the gauge above: the gauge sees the
 *       symptom on disk, the counter sees the cause in flight. A
 *       Grafana alert on either signal catches the same regression
 *       class with belt + suspenders.</li>
 * </ul>
 *
 * <p>Both signals reach {@code /actuator/prometheus} via the
 * Micrometer registry that's already wired by
 * {@code spring-boot-starter-actuator}. No webhook dependency, no
 * external service — the whole point of duplicating this with the
 * {@code PaymentNotifier} alert is so detection survives a
 * mis-configured webhook URL.
 */
@Component
@Slf4j
public class PaymentLogReconciliationMonitor {

    /**
     * Rows committed inside the last {@value} minutes are considered
     * "still potentially mid-flight" by the gauge. The settle path
     * persists the row in one transaction with the tx hash, so this
     * window can be tight; 5 min is generous defence against clock
     * skew / network blips.
     */
    static final int IN_FLIGHT_GRACE_MINUTES = 5;

    private final PaymentLogRepository repo;
    private final AtomicLong gaugeValue = new AtomicLong(0);
    private final Counter reconciliationFailureCounter;

    public PaymentLogReconciliationMonitor(
            PaymentLogRepository repo,
            MeterRegistry meterRegistry
    ) {
        this.repo = repo;

        // Gauge — read by the scrape, computed by the @Scheduled
        // tick. Exposed as `payment_log_reconciliation_gap_rows`
        // (Micrometer naming convention). A value above zero on the
        // production scrape is the alert condition.
        meterRegistry.gauge(
                "payment_log.reconciliation.gap.rows",
                Tags.empty(),
                gaugeValue,
                AtomicLong::doubleValue
        );

        // Counter — incremented by X402SettlementAdvice on the
        // non-duplicate integrity-violation branch. Survives across
        // process restarts via Prometheus's counter monotonicity
        // (the registry resets on restart, but Prometheus's `rate()`
        // and `increase()` queries handle counter resets).
        this.reconciliationFailureCounter = Counter
                .builder("payment_log.reconciliation.failures")
                .description("Settlements where the facilitator confirmed but the payment_log row was rejected by the schema or DB. SLO target: 0 / day.")
                .register(meterRegistry);
    }

    /** Called by {@link X402SettlementAdvice} on the integrity-violation branch. */
    public void recordReconciliationFailure() {
        reconciliationFailureCounter.increment();
    }

    @PostConstruct
    void initialiseGauge() {
        // Take an immediate reading at boot so the gauge isn't 0
        // through the first scheduling interval — operators looking
        // at the dashboard right after a deploy should see real data.
        recompute();
    }

    /**
     * Recomputes the gauge every minute. Cheap query (indexed scan
     * over a tiny table), no DB load worth worrying about. Per-minute
     * cadence means the alert fires within 1-2 minutes of a row
     * being silently dropped — well inside the SLO error budget for
     * a "0 rows" target.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void recompute() {
        try {
            Instant cutoff = Instant.now().minus(IN_FLIGHT_GRACE_MINUTES, ChronoUnit.MINUTES);
            long gap = repo.countNullTxOlderThan(cutoff);
            gaugeValue.set(gap);
            if (gap > 0) {
                // Mirror the gauge into a log line so an operator
                // grepping `docker logs` finds the same signal.
                log.warn("payment_log reconciliation gap: {} rows older than {}min have null facilitator_tx_id — SLO violation",
                        gap, IN_FLIGHT_GRACE_MINUTES);
            }
        } catch (RuntimeException e) {
            // Never let the monitor crash the scheduler thread. A
            // failed read on a single tick is fine — next minute's
            // tick will retry. The gauge stays at its last value.
            log.warn("payment_log reconciliation monitor failed to refresh gauge: {}", e.getMessage());
        }
    }

    /** Test hook — exposes the gauge for assertions. */
    long currentGapForTest() {
        return gaugeValue.get();
    }
}
