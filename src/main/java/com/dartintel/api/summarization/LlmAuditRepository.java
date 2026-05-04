package com.dartintel.api.summarization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmAuditRepository extends JpaRepository<LlmAudit, Long> {

    List<LlmAudit> findByRcptNoOrderByCreatedAtDesc(String rcptNo);

    /**
     * True when at least one successful LLM run was committed for this
     * receipt number. Lets {@link SummaryService} short-circuit before
     * paying for a second LLM call when the first audit row landed but
     * the {@code disclosure_summary} row insert failed — the
     * audit-first-commit pattern guarantees the audit row is committed
     * before the summary row is attempted, so an audit success row in
     * the absence of a summary row indicates a previous partial-write
     * failure that the retry scheduler will eventually heal.
     */
    boolean existsByRcptNoAndSuccessTrue(String rcptNo);
}
