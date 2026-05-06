package com.dartintel.api.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the V10 migration applied cleanly and that
 * {@link RequestAuditRepository}'s pruning + counting queries
 * land on the right rows. Boots Postgres via testcontainers so
 * Flyway runs the real migration; assertions are scoped to
 * scenarios the analytics / persister code actually executes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RequestAuditRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RequestAuditRepository repository;

    @Test
    void persistsAndReadsBackEveryColumn() {
        RequestAudit row = RequestAudit.builder()
                .ts(Instant.parse("2026-05-04T07:38:40Z"))
                .method("POST")
                .path("/v1/disclosures/summary")
                .status(405)
                .ip("104.131.41.96")
                .userAgent("axios/1.14.0")
                .queryKeys("rcptNo")
                .bodyBytes(2L)
                .contentType("application/json")
                .hasXPayment(false)
                .hasPaymentSig(false)
                .build();

        RequestAudit saved = repository.saveAndFlush(row);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMethod()).isEqualTo("POST");
        assertThat(saved.getPath()).isEqualTo("/v1/disclosures/summary");
        assertThat(saved.getStatus()).isEqualTo(405);
        assertThat(saved.getIp()).isEqualTo("104.131.41.96");
        assertThat(saved.getUserAgent()).isEqualTo("axios/1.14.0");
        assertThat(saved.getQueryKeys()).isEqualTo("rcptNo");
        assertThat(saved.getBodyBytes()).isEqualTo(2L);
        assertThat(saved.getContentType()).isEqualTo("application/json");
        assertThat(saved.isHasXPayment()).isFalse();
        assertThat(saved.isHasPaymentSig()).isFalse();
    }

    @Test
    void nullableColumnsRoundTripAsNullsNotPlaceholders() {
        // Free GET request with no headers at all: ip, userAgent,
        // queryKeys, bodyBytes, contentType all null. Confirms the
        // schema accepts all five and the entity preserves null on
        // read so analytics queries can use IS NULL.
        RequestAudit row = RequestAudit.builder()
                .ts(Instant.now())
                .method("GET")
                .path("/v1/pricing")
                .status(200)
                .build();

        RequestAudit saved = repository.saveAndFlush(row);

        assertThat(saved.getIp()).isNull();
        assertThat(saved.getUserAgent()).isNull();
        assertThat(saved.getQueryKeys()).isNull();
        assertThat(saved.getBodyBytes()).isNull();
        assertThat(saved.getContentType()).isNull();
    }

    @Test
    void deleteByTsBeforeRemovesOnlyRowsOlderThanCutoff() {
        Instant now = Instant.now();
        repository.saveAndFlush(audit(now.minus(100, ChronoUnit.DAYS), 200));   // older
        repository.saveAndFlush(audit(now.minus(91, ChronoUnit.DAYS), 200));    // older (boundary - 1)
        repository.saveAndFlush(audit(now.minus(89, ChronoUnit.DAYS), 200));    // newer (boundary + 1)
        repository.saveAndFlush(audit(now.minus(1, ChronoUnit.DAYS), 200));     // newer
        Instant cutoff = now.minus(90, ChronoUnit.DAYS);

        int deleted = repository.deleteByTsBefore(cutoff);

        assertThat(deleted).isEqualTo(2);
        // Survivors stay queryable.
        assertThat(repository.countByTsAfter(cutoff)).isEqualTo(2L);
    }

    @Test
    void countByTsAfterCountsRowsStrictlyAfterCutoff() {
        Instant now = Instant.now();
        repository.saveAndFlush(audit(now.minus(2, ChronoUnit.HOURS), 200));
        repository.saveAndFlush(audit(now.minus(30, ChronoUnit.MINUTES), 405));
        repository.saveAndFlush(audit(now.minus(1, ChronoUnit.MINUTES), 402));

        long lastHour = repository.countByTsAfter(now.minus(1, ChronoUnit.HOURS));

        assertThat(lastHour).isEqualTo(2L);
    }

    private static RequestAudit audit(Instant ts, int status) {
        return RequestAudit.builder()
                .ts(ts).method("GET").path("/v1/companies").status(status)
                .build();
    }
}
