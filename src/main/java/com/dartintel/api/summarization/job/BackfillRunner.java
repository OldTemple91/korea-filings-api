package com.dartintel.api.summarization.job;

import com.dartintel.api.ingestion.DisclosureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Opt-in startup task that scans the disclosure table for rows without a
 * corresponding disclosure_summary and enqueues each into the summary job
 * queue. Exists to prime a freshly-deployed environment (or this laptop)
 * where the ingestion pipeline has already collected filings but the LLM
 * pipeline hasn't run yet.
 *
 * Activate with {@code summary.backfill.enabled=true}. Bounded by
 * {@code summary.backfill.max} (default 500) so a runaway config cannot
 * blow through LLM quota.
 */
@Component
@ConditionalOnProperty(name = "summary.backfill.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BackfillRunner implements ApplicationRunner {

    private final DisclosureRepository disclosureRepository;
    private final SummaryJobQueue queue;

    @Value("${summary.backfill.max:500}")
    private int maxItems;

    @Override
    public void run(ApplicationArguments args) {
        List<String> ids = disclosureRepository
                .findRcptNosMissingSummary(PageRequest.of(0, maxItems));
        if (ids.isEmpty()) {
            log.info("Backfill: no disclosures missing summaries");
            return;
        }
        for (String rcptNo : ids) {
            queue.push(rcptNo);
        }
        log.info("Backfill: enqueued {} disclosures (max={})", ids.size(), maxItems);
    }
}
