package com.dartintel.api.payment;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted coverage for the SQLState differentiation helper that
 * decides whether a {@link DataIntegrityViolationException} is a
 * benign UNIQUE constraint duplicate (idempotent — swallow) or a
 * real schema / row-shape problem (loud — flag for reconciliation).
 *
 * <p>Round-7 introduced a {@code "nonce:" + 0x + 64-hex} replay key
 * shape (72 chars) into a {@code VARCHAR(64)} column. Postgres
 * rejected the insert with SQL state {@code 22001} ("string data,
 * right truncation"), Hibernate wrapped that in
 * {@code DataIntegrityViolationException}, and the previous
 * implementation caught the parent class and silently swallowed the
 * truncation as if it were a benign duplicate. Every paid mainnet
 * settlement under the new replay-key shape was lost from
 * payment_log because of the misclassification — settlements landed
 * on-chain but the DB had no record. V11 widens the column; this
 * helper makes sure a future repeat of the same misclassification
 * cannot happen by inspecting the underlying SQLState rather than
 * the wrapper class.
 */
class X402SettlementAdviceTest {

    @Test
    void unique_violation_23505_is_treated_as_duplicate() {
        SQLException unique = new SQLException("duplicate key", "23505");
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("could not execute statement", unique);

        assertThat(X402SettlementAdvice.isUniqueConstraintViolation(wrapped)).isTrue();
    }

    @Test
    void string_data_right_truncation_22001_is_NOT_treated_as_duplicate() {
        // The exact failure mode the round-7 / round-8 silent-drop bug
        // produced. Must classify as "not a duplicate" so the handler
        // takes the reconciliation-failure branch.
        SQLException tooLong = new SQLException(
                "value too long for type character varying(64)", "22001");
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("could not execute statement", tooLong);

        assertThat(X402SettlementAdvice.isUniqueConstraintViolation(wrapped)).isFalse();
    }

    @Test
    void not_null_violation_23502_is_NOT_treated_as_duplicate() {
        SQLException notNull = new SQLException("null value in column", "23502");
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("could not execute statement", notNull);

        assertThat(X402SettlementAdvice.isUniqueConstraintViolation(wrapped)).isFalse();
    }

    @Test
    void check_violation_23514_is_NOT_treated_as_duplicate() {
        SQLException checkFail = new SQLException(
                "check constraint violated", "23514");
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("could not execute statement", checkFail);

        assertThat(X402SettlementAdvice.isUniqueConstraintViolation(wrapped)).isFalse();
    }

    @Test
    void exception_without_a_jdbc_sqlexception_in_the_chain_is_NOT_treated_as_duplicate() {
        // Spring/Hibernate sometimes wraps in a way that loses the
        // SQLException (e.g. JPA-level uniqueness checks before the
        // DB sees the row). Conservative default: not idempotent.
        DataIntegrityViolationException bare =
                new DataIntegrityViolationException("integrity violation",
                        new IllegalStateException("not a SQLException"));

        assertThat(X402SettlementAdvice.isUniqueConstraintViolation(bare)).isFalse();
    }

    @Test
    void deeply_nested_sqlexception_is_still_found() {
        // Real Spring stacks nest the SQLException several levels
        // deep behind PSQLException → JDBC4PreparedStatement →
        // ConstraintViolationException → DataIntegrityViolationException.
        // Make sure the cause walker keeps unwrapping.
        SQLException unique = new SQLException("dupe", "23505");
        Throwable nested = new RuntimeException("layer 3", unique);
        nested = new RuntimeException("layer 2", nested);
        nested = new RuntimeException("layer 1", nested);
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("top", nested);

        assertThat(X402SettlementAdvice.isUniqueConstraintViolation(wrapped)).isTrue();
    }
}
