package com.dartintel.api.summarization;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SummaryWriter {

    private final DisclosureSummaryRepository summaryRepository;
    private final LlmAuditRepository auditRepository;

    @Transactional(readOnly = true)
    public boolean summaryExists(String rcptNo) {
        return summaryRepository.existsByRcptNo(rcptNo);
    }

    @Transactional
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

    @Transactional
    public void recordAuditFailure(String rcptNo, String model, String promptHash, int latencyMs, String error) {
        auditRepository.save(LlmAudit.failure(rcptNo, model, promptHash, latencyMs, error));
    }

    @Transactional
    public void recordSummary(String rcptNo, SummaryEnvelope env) {
        SummaryResult r = env.result();
        summaryRepository.save(new DisclosureSummary(
                rcptNo,
                r.summaryEn(),
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

    private static List<String> nullSafe(List<String> in) {
        return in != null ? in : List.of();
    }
}
