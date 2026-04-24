package com.dartintel.api.summarization.job;

import com.dartintel.api.ingestion.DisclosureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically scans for disclosures that still have no summary and
 * re-enqueues them for the consumer. Covers:
 *   - filings whose original summarisation hit a rate limit or an open
 *     circuit breaker and were recorded as audit_failure,
 *   - filings where the initial DartPollingScheduler push to Redis
 *     failed because of a transient Redis outage,
 *   - filings whose processing was interrupted when the JVM restarted.
 *
 * The consumer re-checks summaryExists before calling the LLM, so
 * double-enqueueing is harmless (no duplicate LLM spend).
 */
@Component
@ConditionalOnProperty(name = "summary.retry.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SummaryRetryScheduler {

    private final DisclosureRepository disclosureRepository;
    private final SummaryJobQueue queue;

    @Value("${summary.retry.max-per-cycle:100}")
    private int maxPerCycle;

    @Scheduled(fixedDelayString = "${summary.retry.interval-ms:300000}",
            initialDelayString = "${summary.retry.initial-delay-ms:60000}")
    public void retryOrphanedSummaries() {
        List<String> orphans = disclosureRepository
                .findRcptNosMissingSummary(PageRequest.of(0, maxPerCycle));
        if (orphans.isEmpty()) {
            return;
        }
        for (String rcptNo : orphans) {
            queue.push(rcptNo);
        }
        log.info("Retry: re-enqueued {} orphan disclosures (max={})",
                orphans.size(), maxPerCycle);
    }
}
