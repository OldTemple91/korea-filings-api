package com.dartintel.api.api.dto;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.summarization.DisclosureClassifier;
import com.dartintel.api.summarization.DisclosureSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Lightweight free-tier representation of a DART filing — the metadata
 * that DART itself publishes ({@code rcptNo}, {@code ticker},
 * {@code corpName}, {@code reportNm}, {@code rceptDt}) plus, when a
 * paid summary has already been generated and cached for this filing,
 * the AI-derived classification fields that come out of the same LLM
 * pass: {@code importanceScore}, {@code eventType}, {@code sectorTags},
 * {@code tickerTags}, {@code actionableFor}. Returned by
 * {@code /v1/disclosures/recent}.
 *
 * <p>Round-15b — what changed: previously every row only carried the
 * raw DART metadata, even when we already had AI metadata in cache. As
 * a result the free {@code /recent} feed was effectively "what's the
 * Korean filing system saying right now" with no signal about which
 * filings were worth paying for. Round-15b widens the row to carry the
 * AI-derived classification when it's already in the cache — which
 * costs the operator nothing (no extra LLM call, no extra DB write)
 * and tells the agent at a glance which row's full summary is worth
 * the 0.005 USDC. The actual English summary text ({@code summaryEn})
 * still requires the paid {@code /summary} or {@code /by-ticker} call —
 * the free feed reveals enough metadata to decide, not enough to
 * substitute for the paid product.
 *
 * <p>For filings that have not been summarised yet (most of them at
 * any given moment, since summaries are lazily generated on the first
 * paid call), the AI fields are absent from the JSON response thanks
 * to the {@link JsonInclude.Include#NON_NULL} class-level annotation —
 * keeping the wire shape of pre-round-15b agents unchanged.
 *
 * <p>The {@code reportNm} stays in Korean because that is its canonical
 * form on DART; agents that want an English version still pay for the
 * AI summary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentFilingDto(
        String rcptNo,
        String ticker,
        String corpName,
        // ---- Round-18 English surface ----
        // corpNameEn: the KRX-registered English company name,
        //   denormalised at ingestion from company.name_en (100%
        //   populated for listed filers; null for the fund / ELS
        //   issuers that carry no ticker either).
        // reportNmEn: English label for the filing type, derived from
        //   the classified eventType. A category label, not a literal
        //   translation of reportNm — deterministic, zero LLM cost.
        // The Korean corpName / reportNm stay as the canonical DART
        // values so nothing is lost for consumers that want them.
        String corpNameEn,
        String reportNm,
        String reportNmEn,
        LocalDate rceptDt,
        // ---- AI-derived fields, populated only when a cached summary
        // exists. Mirrors DisclosureSummaryDto for shape consistency,
        // minus summaryEn (which is the paid bit).
        Integer importanceScore,
        String eventType,
        List<String> sectorTags,
        List<String> tickerTags,
        List<String> actionableFor,
        // ---- Round-17a free pre-purchase signals (pure functions) ----
        // sourceUrl: canonical DART viewer link (audit path to the
        //   authoritative filing). numericExpectation: HIGH/LOW from the
        //   eventType — lets an agent decide, BEFORE paying, whether the
        //   paid summary is likely to carry an extractable number. Both
        //   derived, zero LLM cost, no hallucination.
        String sourceUrl,
        String numericExpectation
) {

    /**
     * Bare row for a filing whose summary has not been generated yet.
     * AI classification fields null; the DART source link is still
     * present (it only needs the rcptNo).
     */
    public static RecentFilingDto from(Disclosure d) {
        // No cached summary row (pre-round-15c ingests only — every
        // filing since then gets a classifier stub at ingestion). The
        // classifier is a pure function of reportNm, so run it on the
        // fly for an accurate English label instead of mislabelling
        // the row "Other Disclosure". The AI enrichment fields stay
        // null per the round-15b contract: they appear only when a
        // cached summary row exists.
        String eventType = DisclosureClassifier
                .classify(d.getReportNm(), d.getTicker())
                .eventType();
        return new RecentFilingDto(
                d.getRcptNo(),
                d.getTicker(),
                d.getCorpName(),
                d.getCorpNameEng(),
                trimmed(d.getReportNm()),
                DisclosureClassifier.eventLabelEn(eventType),
                d.getRceptDt(),
                null, null, null, null, null,
                DisclosureSummaryDto.dartViewerUrl(d.getRcptNo()),
                null
        );
    }

    /** Defensive normalisation for rows ingested before round-18
     *  collapsed DART's fixed-width padding at write time. */
    private static String trimmed(String reportNm) {
        return Disclosure.normalizeReportNm(reportNm);
    }

    /**
     * Enriched row for a filing whose summary (or classifier stub) is
     * in the cache. The summary text itself is deliberately not
     * surfaced — that's the paid {@code /summary} endpoint — but every
     * classification field plus the DART link and the numeric-content
     * expectation are shared so an agent reading the free feed can
     * rank-order which filings are worth paying for.
     */
    public static RecentFilingDto from(Disclosure d, DisclosureSummary s) {
        return new RecentFilingDto(
                d.getRcptNo(),
                d.getTicker(),
                d.getCorpName(),
                d.getCorpNameEng(),
                trimmed(d.getReportNm()),
                DisclosureClassifier.eventLabelEn(s.getEventType()),
                d.getRceptDt(),
                s.getImportanceScore(),
                s.getEventType(),
                s.getSectorTags(),
                s.getTickerTags(),
                s.getActionableFor(),
                DisclosureSummaryDto.dartViewerUrl(d.getRcptNo()),
                DisclosureClassifier.numericExpectation(s.getEventType())
        );
    }
}
