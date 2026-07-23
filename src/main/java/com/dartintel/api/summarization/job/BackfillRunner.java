package com.dartintel.api.summarization.job;

import com.dartintel.api.summarization.DisclosureSummaryRepository;
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
 * Opt-in startup task that enqueues classifier stubs — summary rows the
 * round-15c ingestion wrote with {@code summary_en = NULL} — into the
 * summary job queue for LLM generation, newest filings first, filtered
 * to an importance floor so quota goes to the disclosures a buyer is
 * most likely to request first.
 *
 * <p>Round-18d rewrite — the original selection ("disclosure rows with
 * no summary row at all") silently became a no-op when round-15c
 * started writing a stub row for <em>every</em> ingested filing: the
 * runner would log "no disclosures missing summaries" against a corpus
 * of tens of thousands of unsummarised stubs. Selection now targets the
 * stubs directly via
 * {@link DisclosureSummaryRepository#findStubRcptNos}.
 *
 * <p>This is a PREPARED, NOT SCHEDULED path: {@code summary.backfill.enabled}
 * defaults to {@code false} and nothing flips it automatically. The
 * intended trigger is a demand signal (an organic paid call, a launch)
 * — see RUNBOOK "Offline backfill". Generation cost is bounded by
 * {@code summary.backfill.max} (default 500) and the consumer inherits
 * the Gemini rate limiter (10 RPM), so a full batch of 500 takes
 * ~50 minutes and cannot starve the paid lazy path of quota.
 */
@Component
@ConditionalOnProperty(name = "summary.backfill.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BackfillRunner implements ApplicationRunner {

    private final DisclosureSummaryRepository summaryRepository;
    private final SummaryJobQueue queue;

    @Value("${summary.backfill.max:500}")
    private int maxItems;

    /**
     * Only stubs at or above this importance score are enqueued.
     * Default 7 = the RIGHTS_OFFERING / MERGER / CONTROL_CHANGE /
     * TRADING_SUSPENSION tier. Set to 1 to backfill everything.
     */
    @Value("${summary.backfill.min-importance:7}")
    private int minImportance;

    @Override
    public void run(ApplicationArguments args) {
        List<String> ids = summaryRepository
                .findStubRcptNos(minImportance, PageRequest.of(0, maxItems));
        if (ids.isEmpty()) {
            log.info("Backfill: no classifier stubs at importance >= {}", minImportance);
            return;
        }
        for (String rcptNo : ids) {
            queue.push(rcptNo);
        }
        log.info("Backfill: enqueued {} stubs (minImportance={}, max={})",
                ids.size(), minImportance, maxItems);
    }
}
