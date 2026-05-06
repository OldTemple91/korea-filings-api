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
import static org.assertj.core.api.Assertions.catchThrowable;

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
    void duplicateSignatureHashIsRejectedByUniqueConstraintWithSqlState23505() {
        repository.saveAndFlush(new PaymentLog(
                null, "/v1/disclosures/latest",
                new BigDecimal("0.003000"), "0xpayer",
                "eip155:84532", null, "d".repeat(64)
        ));

        // Hibernate's JPA dialect surfaces UNIQUE violations as the
        // parent DataIntegrityViolationException — NOT as Spring's
        // DuplicateKeyException subclass. The settlement advice's
        // idempotency guard relies on inspecting the JDBC SQLState
        // ("23505" = unique_violation in Postgres) inside the catch
        // block; an earlier attempt to differentiate by exception
        // subclass alone would silently fail because Hibernate never
        // produces that subclass under JPA. Lock in both facts here
        // so any future change to the dialect / driver surfaces in
        // this test, not in production.
        Throwable thrown = catchThrowable(() -> repository.saveAndFlush(new PaymentLog(
                null, "/v1/disclosures/latest",
                new BigDecimal("0.003000"), "0xother",
                "eip155:84532", null, "d".repeat(64)
        )));
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(unwrapSqlState(thrown)).isEqualTo("23505");
    }

    /**
     * Walks the cause chain looking for the underlying SQLException
     * — same approach the production handler uses. Pulls the SQLState
     * out so the test asserts on the exact code.
     */
    private static String unwrapSqlState(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    /**
     * Regression test for the round-7 / round-8 silent-drop bug.
     *
     * <p>{@code X402PaywallInterceptor.replayKey} returns {@code "nonce:" + 0x + 64-hex}
     * = 72 characters when the EIP-3009 nonce is present. The original
     * column was VARCHAR(64), which Postgres rejects with SQL state
     * 22001 ("value too long"). V11 widens the column to 96.
     *
     * <p>This test asserts:
     * <ol>
     *   <li>A 72-character signature hash now persists cleanly.</li>
     *   <li>The {@code @Column(length = 96)} on the entity matches
     *       the migration so {@code spring.jpa.hibernate.ddl-auto=validate}
     *       does not reject the schema at boot.</li>
     * </ol>
     */
    @Test
    void noncePrefixedSignatureHashFitsAfterV11Widening() {
        String prefixedNonce = "nonce:0x" + "f".repeat(64); // 72 chars
        PaymentLog saved = repository.saveAndFlush(new PaymentLog(
                "20260424900874",
                "/v1/disclosures/summary?rcptNo=20260424900874",
                new BigDecimal("0.005000"),
                "0x254A42D7c617B38c7B43186e892d3af4bf9D6c44",
                "eip155:8453",
                "0xabc123",
                prefixedNonce
        ));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSignatureHash()).isEqualTo(prefixedNonce);
        assertThat(saved.getSignatureHash().length()).isEqualTo(72);
    }
}
