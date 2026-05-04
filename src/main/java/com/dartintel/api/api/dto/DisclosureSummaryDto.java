package com.dartintel.api.api.dto;

import com.dartintel.api.summarization.DisclosureSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Paid API response shape for {@code GET /v1/disclosures/summary?rcptNo=...}.
 * Exposes a subset of DisclosureSummary — operational fields like
 * model_used / token counts / cost_usd stay server-side.
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
        Instant generatedAt
) {

    public static DisclosureSummaryDto from(DisclosureSummary s) {
        return new DisclosureSummaryDto(
                s.getRcptNo(),
                s.getSummaryEn(),
                s.getImportanceScore(),
                s.getEventType(),
                s.getSectorTags(),
                s.getTickerTags(),
                s.getActionableFor(),
                s.getGeneratedAt()
        );
    }
}
