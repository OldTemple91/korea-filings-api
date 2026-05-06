package com.dartintel.api.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronously writes {@link RequestAudit} rows to Postgres.
 *
 * <p>Sits behind {@link RequestAuditFilter} via an in-process bounded
 * queue. The filter calls {@link #enqueue(RequestAudit)} and returns
 * immediately; a single dedicated daemon thread drains the queue and
 * batch-INSERTs up to {@value #BATCH_SIZE} rows per round-trip. The
 * filter never blocks on Postgres — at worst the queue fills under
 * sustained DB latency and new rows are dropped with a WARN.
 *
 * <p>Pruning runs once per night via {@code @Scheduled}; rows older
 * than {@value #RETENTION_DAYS} days are deleted. 90 days is enough
 * for monthly cohort comparisons and stays under the disk ceiling for
 * any realistic v1.x traffic — at the current rate (~500 audited
 * requests/day), 90 days = 45k rows ≈ a few MB.
 *
 * <p>Disabled by default. Enable in prod with
 * {@code REQUEST_AUDIT_PERSIST=true}. The filter still logs
 * unconditionally when {@code REQUEST_AUDIT_ENABLED=true}; persistence
 * is the additive layer that turns those log lines into queryable
 * history. Both flags are independent so a noisy load test can keep
 * structured logs without filling the DB.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "audit.requests.persist", havingValue = "true")
public class RequestAuditPersister {

    /** Cap on the in-memory backlog. Roughly two minutes of headroom at our current rate. */
    static final int QUEUE_CAPACITY = 10_000;
    /** Max rows per INSERT round-trip. Hibernate batches ~50-200 efficiently. */
    static final int BATCH_SIZE = 100;
    /** Drain blocks at most this long before checking shutdown. */
    private static final long POLL_TIMEOUT_MS = 1_000L;
    /** How many days of history we keep before nightly pruning sweeps it. */
    static final int RETENTION_DAYS = 90;

    private final RequestAuditRepository repo;
    private final BlockingQueue<RequestAudit> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private volatile boolean running = true;
    /** Tracks dropped rows so we WARN once per burst rather than once per drop. */
    private volatile long droppedSinceLastWarn = 0;

    public RequestAuditPersister(RequestAuditRepository repo) {
        this.repo = repo;
        this.worker = new Thread(this::drainLoop, "req-audit-persister");
        this.worker.setDaemon(true);
    }

    @PostConstruct
    void start() {
        worker.start();
        log.info("RequestAuditPersister started (queue capacity={}, retention={}d)",
                QUEUE_CAPACITY, RETENTION_DAYS);
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Best-effort drain on the way out so a clean shutdown doesn't
        // lose the last few rows. Bypasses the worker since it's already
        // joined.
        if (!queue.isEmpty()) {
            List<RequestAudit> remaining = new ArrayList<>(queue.size());
            queue.drainTo(remaining);
            try {
                repo.saveAll(remaining);
                log.info("Flushed {} pending audit rows on shutdown", remaining.size());
            } catch (RuntimeException e) {
                log.error("Failed to flush {} audit rows on shutdown: {}",
                        remaining.size(), e.getMessage());
            }
        }
    }

    /**
     * Non-blocking enqueue. Drops the row (with a coalesced WARN) if
     * the queue is full — the caller is in the request hot path and
     * we never block it on persistence.
     */
    public void enqueue(RequestAudit row) {
        if (!queue.offer(row)) {
            droppedSinceLastWarn++;
            // Coalesce drop warnings — log once per 100 drops so a
            // 30-second burst of saturated traffic doesn't produce
            // 30 thousand log lines.
            if (droppedSinceLastWarn % 100 == 1) {
                log.warn("audit queue full ({}/{}); dropped {} rows since last warning",
                        queue.size(), QUEUE_CAPACITY, droppedSinceLastWarn);
            }
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                RequestAudit head = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (head == null) {
                    continue;
                }
                List<RequestAudit> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(head);
                queue.drainTo(batch, BATCH_SIZE - 1);
                try {
                    repo.saveAll(batch);
                } catch (RuntimeException e) {
                    // DB hiccup — log once and keep going. Rows in
                    // this batch are lost; alternative would be
                    // re-enqueueing, but that risks an infinite loop
                    // if the DB is permanently broken (e.g. schema
                    // drift). The persister exists to reduce our
                    // visibility into agent traffic, not to gate
                    // request flow, so dropping is the safer default.
                    log.error("audit batch insert failed (rows lost: {}): {}",
                            batch.size(), e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Daily prune at 04:00 UTC — outside Korean trading hours (KST 13:00),
     * outside US trading hours (PT 21:00 prev day), and well after our
     * 06:00 UTC encrypted Postgres backup so the prune doesn't compete
     * with pg_dump for I/O.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void pruneOldRows() {
        try {
            int deleted = repo.deleteByTsBefore(
                    Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
            if (deleted > 0) {
                log.info("Pruned {} request_audit rows older than {} days",
                        deleted, RETENTION_DAYS);
            }
        } catch (RuntimeException e) {
            log.error("audit prune failed: {}", e.getMessage());
        }
    }

    /** Test hook — exposes the live backlog for assertion. */
    int queueSize() {
        return queue.size();
    }
}
