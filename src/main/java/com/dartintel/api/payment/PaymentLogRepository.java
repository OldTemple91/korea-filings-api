package com.dartintel.api.payment;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentLogRepository extends JpaRepository<PaymentLog, Long> {

    boolean existsBySignatureHash(String signatureHash);

    /**
     * Counts payment_log rows missing a {@code facilitator_tx_id} that
     * are older than the given cutoff. The {@code SLO.md} target is
     * <em>0 rows</em> — a non-zero value means a settlement landed
     * but the on-chain tx hash never made it onto the row.
     *
     * <p>Used by {@link PaymentLogReconciliationMonitor} as a
     * Micrometer gauge so any future regression of the round-7 silent-
     * drop class surfaces in Prometheus / Grafana within minutes,
     * rather than waiting for a manual TS SDK live test.
     *
     * <p>The cutoff (typically NOW - 5 minutes) excludes rows that
     * are mid-flight — the settle path persists the row in a single
     * transaction with non-null tx hash, but a request that's still
     * inside that transaction window would otherwise count.
     */
    @Query("select count(p) from PaymentLog p where p.facilitatorTxId is null and p.settledAt < :cutoff")
    long countNullTxOlderThan(@Param("cutoff") Instant cutoff);
}
