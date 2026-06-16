package com.dartintel.api.api.dto;

import com.dartintel.api.summarization.DisclosureClassifier;
import com.dartintel.api.summarization.DisclosureSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Paid API response shape for {@code GET /v1/disclosures/summary?rcptNo=...}.
 * Exposes a subset of DisclosureSummary — operational fields like
 * model_used / token counts / cost_usd stay server-side.
 *
 * <p>Round-17a additions, both pure functions (no DB, no LLM):
 * <ul>
 *   <li>{@code sourceUrl} — the canonical DART viewer link for this
 *       filing. The summary is a paraphrase, never verbatim; a quant /
 *       research workflow cannot adopt a paraphrase without an audit
 *       path back to the authoritative original, so this is the trust
 *       gate for repeat calls on money-relevant data. It also makes the
 *       long-standing {@code llms.txt} claim ("every summary links back
 *       to the DART original") true. Derived from the stored
 *       {@code rcptNo}.</li>
 *   <li>{@code numericExpectation} — {@code HIGH}/{@code LOW}, derived
 *       from the eventType, signalling whether this filing class
 *       normally carries an extractable number. Also surfaced on the
 *       FREE {@code /recent} feed so an agent can decide before paying.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisclosureSummaryDto(
        String rcptNo,
        String summaryEn,
        int importanceScore,
        String eventType,
        List<String> sectorTags,
        List<String> tickerTags,
        List<String> actionableFor,
        String sourceUrl,
        String numericExpectation,
        Instant generatedAt
) {

    /** Canonical DART document-viewer URL for a 14-digit receipt number. */
    public static String dartViewerUrl(String rcptNo) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rcptNo;
    }

    public static DisclosureSummaryDto from(DisclosureSummary s) {
        return new DisclosureSummaryDto(
                s.getRcptNo(),
                s.getSummaryEn(),
                s.getImportanceScore(),
                s.getEventType(),
                s.getSectorTags(),
                s.getTickerTags(),
                s.getActionableFor(),
                dartViewerUrl(s.getRcptNo()),
                DisclosureClassifier.numericExpectation(s.getEventType()),
                s.getGeneratedAt()
        );
    }
}
