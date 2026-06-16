package com.dartintel.api.summarization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Guardrail counter for the "paid endpoint served a degraded response"
 * class of bug — specifically a paid {@code /v1/disclosures/by-ticker}
 * or {@code /v1/disclosures/summary} call that could not return a real
 * LLM-generated English summary for a slot the caller paid for.
 *
 * <p>Why this exists: round-15c added a rule-based classifier that
 * writes a stub {@code disclosure_summary} row (with {@code summary_en
 * = NULL}) for every ingested filing. The two paid controller handlers
 * checked only row <em>existence</em> ("is there a cached summary?")
 * rather than LLM-summary <em>presence</em> ("does that row have the
 * English text?"). The stub satisfied the existence check, so the LLM
 * generation was skipped and the paid response shipped with a null
 * {@code summaryEn} — the customer paid full price for an empty
 * product. The regression went unnoticed for two weeks because nothing
 * counted "we served a paid slot with no summary text." This counter
 * is that missing signal: a Grafana alert on
 * {@code paid_summary_degraded_total} fires the moment a paid call
 * cannot be fully served, so the same class of regression is caught in
 * minutes, not by a manual audit of payment_log.
 *
 * <p>The expected steady-state value is 0. A non-zero increment means
 * either (a) LLM generation genuinely failed for a filing the caller
 * paid for (a Gemini outage — transient, recoverable), or (b) a code
 * path is again serving stubs on a paid call (a regression — needs a
 * fix). The {@code endpoint} tag distinguishes which surface, and the
 * accompanying WARN log line carries the count for {@code docker logs}
 * grep parity.
 */
@Component
@Slf4j
public class PaidSummaryQualityMonitor {

    private final MeterRegistry meterRegistry;

    public PaidSummaryQualityMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Record that {@code count} paid slots on {@code endpoint} could
     * not be served a real LLM summary. Increments
     * {@code paid_summary_degraded_total{endpoint="..."}} and emits a
     * WARN so the signal is visible in both Prometheus and the logs.
     *
     * @param endpoint short endpoint label, e.g. {@code "by-ticker"} or {@code "summary"}
     * @param count    number of degraded slots (≥ 1)
     */
    public void recordDegraded(String endpoint, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("paid_summary.degraded")
                .description("Paid summary slots that could not be served a real LLM-generated English summary (stub served or generation failed). SLO target: 0.")
                .tag("endpoint", endpoint)
                .register(meterRegistry)
                .increment(count);
        log.warn("paid response degraded: endpoint={} slots_without_llm_summary={} "
                        + "(generation failure or stub regression — caller paid but got no summary text)",
                endpoint, count);
    }
}
