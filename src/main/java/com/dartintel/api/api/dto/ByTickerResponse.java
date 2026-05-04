package com.dartintel.api.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Paid-endpoint response shape for
 * {@code GET /v1/disclosures/by-ticker?ticker=…&limit=N}. The
 * {@code chargedFor} and {@code delivered} fields exist so a paying
 * agent can reconcile what they paid for against what the server
 * actually returned — they diverge when the ticker has fewer recent
 * filings than {@code limit}, or when one of those filings does not
 * yet have an AI summary in cache.
 */
public record ByTickerResponse(
        @Schema(example = "005930", description = "Echoed back from the request.")
        String ticker,

        @Schema(description =
                "Alias of `delivered`, kept for v0.2.x SDK shape compatibility. " +
                "Use `delivered` and `chargedFor` for the unambiguous accounting.")
        int count,

        @Schema(description =
                "The `limit` query parameter the agent paid for. Equal to the " +
                "multiplier the 402 challenge's `accepts[0].amount` was scaled by.")
        int chargedFor,

        @Schema(description =
                "How many summaries were actually returned. May be less than " +
                "`chargedFor` when the ticker has fewer recent filings than " +
                "`limit`, or when one of those filings does not yet have a " +
                "cached summary.")
        int delivered,

        @Schema(description = "AI summaries, newest first. Length equals `delivered`.")
        List<DisclosureSummaryDto> summaries
) {
}
