package com.dartintel.api.summarization.job;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.DisclosureClassifier;
import com.dartintel.api.summarization.DisclosureSummary;
import com.dartintel.api.summarization.DisclosureSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Opt-in startup sweep that re-applies the CURRENT classifier rule set
 * to every classifier stub ({@code summary_en IS NULL}). A stub's
 * eventType / importance / tags are frozen at ingestion time under
 * whatever rules existed that day, so every rule addition (round-15c →
 * 18e grew the set from ~40 to ~70 rules) leaves the historical feed
 * describing filings under the old taxonomy — e.g. 5,573 rows sat in
 * OTHER when the 18e rules would classify most of them.
 *
 * <p>Pure function of {@code report_nm} + {@code ticker}: no LLM call,
 * no cost, idempotent (re-running when nothing changed writes nothing).
 * Rows carrying an LLM summary are immutable product and are never
 * touched — {@link DisclosureSummary#applyReclassification} enforces
 * that at the entity level as well.
 *
 * <p>Activate for one boot with {@code summary.reclassify.enabled=true}
 * (env {@code SUMMARY_RECLASSIFY_ENABLED} — passed through
 * docker-compose explicitly; the round-18d backfill misfire taught us
 * the enumerated env list silently drops anything not listed).
 */
@Component
@ConditionalOnProperty(name = "summary.reclassify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ReclassifyRunner implements ApplicationRunner {

    private static final int PAGE_SIZE = 500;

    private final DisclosureSummaryRepository summaryRepository;
    private final DisclosureRepository disclosureRepository;

    @Override
    public void run(ApplicationArguments args) {
        int scanned = 0;
        int changed = 0;
        for (int page = 0; ; page++) {
            List<DisclosureSummary> stubs = summaryRepository
                    .findBySummaryEnIsNullOrderByRcptNo(PageRequest.of(page, PAGE_SIZE));
            if (stubs.isEmpty()) {
                break;
            }
            scanned += stubs.size();
            Map<String, Disclosure> byRcptNo = disclosureRepository
                    .findAllById(stubs.stream().map(DisclosureSummary::getRcptNo).toList())
                    .stream()
                    .collect(Collectors.toMap(Disclosure::getRcptNo, Function.identity()));
            List<DisclosureSummary> dirty = new ArrayList<>();
            for (DisclosureSummary stub : stubs) {
                Disclosure d = byRcptNo.get(stub.getRcptNo());
                if (d == null) {
                    continue; // orphan stub — nothing to classify from
                }
                DisclosureClassifier.Classification c =
                        DisclosureClassifier.classify(d.getReportNm(), d.getTicker());
                if (stub.applyReclassification(c)) {
                    dirty.add(stub);
                }
            }
            if (!dirty.isEmpty()) {
                summaryRepository.saveAll(dirty);
                changed += dirty.size();
            }
        }
        log.info("Reclassify: scanned {} stubs, updated {} to the current rule set",
                scanned, changed);
    }
}
