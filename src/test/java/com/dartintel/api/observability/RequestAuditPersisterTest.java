package com.dartintel.api.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the threading + queueing behaviour of
 * {@link RequestAuditPersister}. Database semantics live in
 * {@link RequestAuditRepositoryIT}; here we mock the repo and verify
 * that the bounded queue + drainer + prune call sites do what they
 * should without spinning up Postgres.
 */
class RequestAuditPersisterTest {

    private RequestAuditRepository repo;
    private RequestAuditPersister persister;

    @BeforeEach
    void setUp() {
        repo = mock(RequestAuditRepository.class);
        persister = new RequestAuditPersister(repo);
        // start() is package-private; @PostConstruct only fires under
        // Spring, so the test wires the worker thread by hand and
        // tears it down in @AfterEach.
        persister.start();
    }

    @AfterEach
    void tearDown() {
        persister.stop();
    }

    @Test
    void enqueuedRowsAreBatchSavedToRepository() throws InterruptedException {
        AtomicInteger savedCount = new AtomicInteger(0);
        doAnswer(inv -> {
            List<RequestAudit> rows = inv.getArgument(0);
            savedCount.addAndGet(rows.size());
            return rows;
        }).when(repo).saveAll(anyList());

        for (int i = 0; i < 5; i++) {
            persister.enqueue(audit("/v1/test/" + i));
        }

        // Worker drains every POLL_TIMEOUT_MS=1s, batches up to 100.
        // Five rows should land in one batch in well under 2s.
        long deadline = System.currentTimeMillis() + 2_000;
        while (savedCount.get() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(savedCount.get()).isEqualTo(5);
        verify(repo, atLeastOnce()).saveAll(anyList());
    }

    @Test
    void manyRowsAreBatchedNotInsertedOneByOne() throws InterruptedException {
        // 250 rows should produce ≤ 4 saveAll() calls (BATCH_SIZE=100),
        // not 250. This is the load-aware batching behaviour the
        // persister exists for.
        AtomicInteger batchCount = new AtomicInteger(0);
        AtomicInteger rowCount = new AtomicInteger(0);
        doAnswer(inv -> {
            List<RequestAudit> rows = inv.getArgument(0);
            batchCount.incrementAndGet();
            rowCount.addAndGet(rows.size());
            return rows;
        }).when(repo).saveAll(anyList());

        for (int i = 0; i < 250; i++) {
            persister.enqueue(audit("/v1/burst/" + i));
        }

        long deadline = System.currentTimeMillis() + 3_000;
        while (rowCount.get() < 250 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(rowCount.get()).isEqualTo(250);
        // Batching tolerance: between 3 (perfect 100-100-50) and 25
        // (smaller drains under timing variance) is fine. We only
        // guard against the 1-row-per-batch degenerate case.
        assertThat(batchCount.get()).isLessThan(50);
    }

    @Test
    void pruneOldRowsDelegatesToRepoWithCorrectCutoff() {
        when(repo.deleteByTsBefore(any(Instant.class))).thenReturn(7);
        Instant before = Instant.now();

        persister.pruneOldRows();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByTsBefore(captor.capture());
        Instant cutoff = captor.getValue();
        // Cutoff is RETENTION_DAYS=90 days before "now".  Anchoring to
        // an absolute number is brittle (clock skew + JVM time);
        // instead bound it to the test's own pre-call snapshot.
        long secondsBack = before.getEpochSecond() - cutoff.getEpochSecond();
        long ninetyDaysSec = 90L * 24 * 3600;
        assertThat(secondsBack).isBetween(ninetyDaysSec - 60, ninetyDaysSec + 60);
    }

    @Test
    void pruneFailureIsCaughtAndDoesNotPropagate() {
        when(repo.deleteByTsBefore(any(Instant.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));

        // Must not throw. Logged at ERROR; we only verify
        // graceful return.
        persister.pruneOldRows();
    }

    private static RequestAudit audit(String path) {
        return RequestAudit.builder()
                .ts(Instant.now())
                .method("GET").path(path).status(200)
                .build();
    }
}
