package com.dartintel.api.summarization;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * All write methods use {@code Propagation.REQUIRES_NEW} so the
 * audit-first-commit invariant holds regardless of caller. The
 * {@link SummaryService#summarize} pattern is:
 *
 * <ol>
 *   <li>Run the LLM call.</li>
 *   <li>Commit a row to {@code llm_audit} (success or failure).</li>
 *   <li>If success, commit the {@code disclosure_summary} row in a
 *       SECOND transaction.</li>
 * </ol>
 *
 * Without {@code REQUIRES_NEW}, a future caller that wraps
 * {@code summarize} in its own transaction would silently turn both
 * writes into a single atomic commit — meaning a downstream summary
 * insert failure would also roll back the audit row, erasing the
 * record that the LLM provider was paid. {@code REQUIRES_NEW}
 * guarantees the audit commit lands on the wire before
 * {@code recordSummary} runs.
 */
@Component
@RequiredArgsConstructor
public class SummaryWriter {

    private final DisclosureSummaryRepository summaryRepository;
    private final LlmAuditRepository auditRepository;

    @Transactional(readOnly = true)
    public boolean summaryExists(String rcptNo) {
        return summaryRepository.existsByRcptNo(rcptNo);
    }

    @Transactional(readOnly = true)
    public boolean auditSuccessExists(String rcptNo) {
        return auditRepository.existsByRcptNoAndSuccessTrue(rcptNo);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuditSuccess(String rcptNo, SummaryEnvelope env, String promptHash) {
        auditRepository.save(LlmAudit.success(
                rcptNo,
                env.model(),
                promptHash,
                env.inputTokens(),
                env.outputTokens(),
                (int) env.latencyMs(),
                env.costUsd()
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuditFailure(String rcptNo, String model, String promptHash, int latencyMs, String error) {
        auditRepository.save(LlmAudit.failure(rcptNo, model, promptHash, latencyMs, error));
    }

    /** Soft cap for {@code summary_en} — column is TEXT (V8) but the
     *  prompt asks for ≤ 400 chars, so a model that ignores the
     *  instruction lands here as a hard ceiling. 800 leaves room for
     *  legitimate complex filings while keeping the response body
     *  agent-friendly. */
    private static final int SUMMARY_EN_MAX = 800;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSummary(String rcptNo, SummaryEnvelope env) {
        SummaryResult r = env.result();
        summaryRepository.save(new DisclosureSummary(
                rcptNo,
                truncate(r.summaryEn(), SUMMARY_EN_MAX),
                r.importanceScore(),
                r.eventType(),
                nullSafe(r.sectorTags()),
                nullSafe(r.tickerTags()),
                nullSafe(r.actionableFor()),
                env.model(),
                env.inputTokens(),
                env.outputTokens(),
                env.costUsd()
        ));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static List<String> nullSafe(List<String> in) {
        return in != null ? in : List.of();
    }
}
