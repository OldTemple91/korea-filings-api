package com.dartintel.api.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PaymentLogRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PaymentLogRepository repository;

    @Test
    void persistsPaymentLogWithTxHashAndSettledTimestamp() {
        PaymentLog log = new PaymentLog(
                "20260423000001",
                "/v1/disclosures/summary?rcptNo=20260423000001",
                new BigDecimal("0.005000"),
                "0x857b06519E91e3A54538791bDbb0E22373e36b66",
                "eip155:84532",
                "0xabc123def456",
                "a".repeat(64)
        );

        PaymentLog saved = repository.saveAndFlush(log);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSettledAt()).isNotNull();
        assertThat(saved.getAmountUsdc()).isEqualByComparingTo(new BigDecimal("0.005000"));
        assertThat(saved.getPayerAddress()).isEqualTo("0x857b06519E91e3A54538791bDbb0E22373e36b66");
    }

    @Test
    void existsBySignatureHashReflectsPersistedRow() {
        repository.saveAndFlush(new PaymentLog(
                null,
                "/v1/disclosures/latest",
                new BigDecimal("0.003000"),
                "0xpayer",
                "eip155:84532",
                null,
                "b".repeat(64)
        ));

        assertThat(repository.existsBySignatureHash("b".repeat(64))).isTrue();
        assertThat(repository.existsBySignatureHash("c".repeat(64))).isFalse();
    }

    @Test
    void duplicateSignatureHashIsRejectedByUniqueConstraint() {
        repository.saveAndFlush(new PaymentLog(
                null, "/v1/disclosures/latest",
                new BigDecimal("0.003000"), "0xpayer",
                "eip155:84532", null, "d".repeat(64)
        ));

        assertThatThrownBy(() -> repository.saveAndFlush(new PaymentLog(
                null, "/v1/disclosures/latest",
                new BigDecimal("0.003000"), "0xother",
                "eip155:84532", null, "d".repeat(64)
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
