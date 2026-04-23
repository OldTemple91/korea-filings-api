package com.dartintel.api.summarization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LlmAuditRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LlmAuditRepository repository;

    @Test
    void persistsSuccessAuditWithTokensAndCost() {
        LlmAudit audit = LlmAudit.success(
                "20260423000001",
                "gemini-2.5-flash-lite",
                "0".repeat(64),
                142,
                89,
                834,
                new BigDecimal("0.00005000")
        );

        LlmAudit saved = repository.saveAndFlush(audit);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getInputTokens()).isEqualTo(142);
        assertThat(saved.getOutputTokens()).isEqualTo(89);
        assertThat(saved.getLatencyMs()).isEqualTo(834);
        assertThat(saved.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.00005000"));
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void persistsFailureAuditWithNullableFieldsNull() {
        LlmAudit audit = LlmAudit.failure(
                "20260423000002",
                "gemini-2.5-flash-lite",
                "1".repeat(64),
                3120,
                "RATE_LIMIT_EXCEEDED: 429"
        );

        LlmAudit saved = repository.saveAndFlush(audit);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isSuccess()).isFalse();
        assertThat(saved.getInputTokens()).isNull();
        assertThat(saved.getOutputTokens()).isNull();
        assertThat(saved.getCostUsd()).isNull();
        assertThat(saved.getErrorMessage()).isEqualTo("RATE_LIMIT_EXCEEDED: 429");
        assertThat(saved.getLatencyMs()).isEqualTo(3120);
    }

    @Test
    void findByRcptNoOrdersByCreatedAtDesc() {
        repository.saveAndFlush(LlmAudit.failure(
                "20260423000003", "gemini-2.5-flash-lite", "a".repeat(64), 1500, "first"));
        repository.saveAndFlush(LlmAudit.success(
                "20260423000003", "gemini-2.5-flash-lite", "b".repeat(64),
                100, 50, 700, new BigDecimal("0.00003000")));

        List<LlmAudit> rows = repository.findByRcptNoOrderByCreatedAtDesc("20260423000003");

        assertThat(rows).hasSize(2);
        // Newest first: success row was saved second.
        assertThat(rows.get(0).isSuccess()).isTrue();
        assertThat(rows.get(1).isSuccess()).isFalse();
    }
}
