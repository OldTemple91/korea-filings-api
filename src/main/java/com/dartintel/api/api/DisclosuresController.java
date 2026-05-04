package com.dartintel.api.api;

import com.dartintel.api.api.dto.ByTickerResponse;
import com.dartintel.api.api.dto.DisclosureSummaryDto;
import com.dartintel.api.api.dto.RecentFilingDto;
import com.dartintel.api.api.dto.RecentFilingsResponse;
import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.summarization.DisclosureSummary;
import com.dartintel.api.summarization.DisclosureSummaryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/disclosures")
@RequiredArgsConstructor
@Validated
@Tag(name = "Disclosures", description = "DART disclosure intelligence — free metadata browsing and paid AI summaries.")
public class DisclosuresController {

    private final DisclosureSummaryRepository summaryRepository;
    private final DisclosureRepository disclosureRepository;

    /**
     * Free metadata feed of recent filings across every listed company.
     * The intended agent flow is: hit this to discover what just
     * happened, then either pay for a single summary via
     * {@code /{rcptNo}/summary} or for a company-scoped batch via
     * {@code /by-ticker/{ticker}}.
     */
    @GetMapping("/recent")
    @SecurityRequirements // unauthenticated, no payment required
    @Operation(
            summary = "List recent DART filings (metadata only, free)",
            description = """
                    Returns the most-recent DART filings across every listed
                    Korean company in metadata-only form (no AI summary). Useful
                    as a discovery feed before deciding which filings warrant a
                    paid summary call.
                    """
    )
    public ResponseEntity<RecentFilingsResponse> getRecent(
            @Parameter(description = "Max filings to return (1-100, default 20).")
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @Parameter(description = "Look back this many hours (1-168, default 24).")
            @RequestParam(value = "since_hours", defaultValue = "24") @Min(1) @Max(168) int sinceHours
    ) {
        Instant threshold = Instant.now().minus(sinceHours, ChronoUnit.HOURS);
        List<RecentFilingDto> filings = disclosureRepository
                .findRecentSince(threshold, PageRequest.of(0, limit))
                .stream()
                .map(RecentFilingDto::from)
                .toList();
        return ResponseEntity.ok(new RecentFilingsResponse(filings));
    }

    /**
     * Paid batch of AI summaries for one Korean company. Pricing scales
     * linearly with {@code limit} via the {@link X402Paywall} per-result
     * mode — agents declare their batch size up front and the 402
     * response advertises {@code limit × 0.005 USDC}.
     *
     * <p>Inputs use query parameters (not path parameters) so the bazaar
     * v1 input schema, which only has buckets for {@code queryParams},
     * {@code bodyFields}, and {@code headerFields}, can faithfully
     * declare the {@code ticker} requirement to autonomous x402 agents.
     * Path parameters silently dropped through the OpenAPI → bazaar
     * translation in earlier versions, leaving the endpoint discoverable
     * but un-callable from raw discovery.
     */
    @GetMapping("/by-ticker")
    @X402Paywall(
            priceUsdc = "0.005",
            pricingMode = X402Paywall.Mode.PER_RESULT,
            countQueryParam = "limit",
            defaultCount = 5,
            maxCount = 50,
            requiredQueryParams = {"ticker"},
            description = "AI summaries for the most recent DART filings of a Korean ticker"
    )
    @Operation(
            summary = "Get AI summaries for the most recent filings of a Korean ticker",
            description = """
                    Costs **0.005 USDC × limit** on Base, settled via x402.
                    Default limit is 5 → 0.025 USDC. Limit is capped at 50.
                    Returns up to {@code limit} summaries, newest first; if
                    the company has fewer recent filings than requested the
                    response is shorter and the agent has overpaid for the
                    missing slots — pre-filter with {@code /v1/disclosures/recent}
                    if budget is tight.

                    Workflow: call free `/v1/companies?q=<name>` to resolve a
                    Korean company name to its six-digit KRX ticker, then pass
                    that ticker here.
                    """
            // x-payment-info extension is injected at runtime by
            // X402OpenApiCustomizer so the wallet, network, asset
            // address, and EIP-712 token name/version match the
            // currently-running x402 config rather than a hardcoded
            // mainnet snapshot.
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment accepted; up to `limit` summaries returned, newest first.",
                    content = @Content(schema = @Schema(implementation = ByTickerResponse.class))),
            @ApiResponse(
                    responseCode = "402",
                    description = "Payment required — body carries the x402 `accepts` block with the limit-scaled amount.",
                    content = @Content(mediaType = "application/json"))
    })
    public ResponseEntity<ByTickerResponse> getByTicker(
            @Parameter(description = "Six-digit KRX ticker, e.g. `005930` for Samsung Electronics. " +
                    "Use the free `/v1/companies?q=<name>` endpoint first to resolve a company name.",
                    example = "005930", required = true)
            @RequestParam("ticker")
            @NotBlank
            @Pattern(regexp = "^[0-9A-Z]{6,7}$",
                    message = "ticker must be 6–7 alphanumeric characters (KRX SPAC tickers can include letters)")
            String ticker,
            @Parameter(description = "Max filings to return (1-50, default 5). Each costs 0.005 USDC.")
            @RequestParam(value = "limit", defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        List<Disclosure> recent = disclosureRepository
                .findByTickerRecent(ticker, PageRequest.of(0, limit));
        // Single bulk lookup instead of N findById calls — keeps
        // get-by-ticker latency O(1) DB round-trips even at the 50-row
        // ceiling. The result preserves recent's order via lookup map.
        List<String> rcptNos = recent.stream().map(Disclosure::getRcptNo).toList();
        Map<String, DisclosureSummary> byRcptNo = summaryRepository.findAllById(rcptNos)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        DisclosureSummary::getRcptNo,
                        s -> s,
                        (a, b) -> a));
        List<DisclosureSummaryDto> summaries = recent.stream()
                .map(d -> byRcptNo.get(d.getRcptNo()))
                .filter(java.util.Objects::nonNull)
                .map(DisclosureSummaryDto::from)
                .toList();
        // chargedFor / delivered diverge when a ticker has fewer recent
        // filings than `limit`, or when one of those filings does not
        // yet have an AI summary in cache. `count` is the legacy alias
        // of `delivered` retained for v0.2.x SDK shape compatibility.
        return ResponseEntity.ok(new ByTickerResponse(
                ticker,
                summaries.size(),
                limit,
                summaries.size(),
                summaries
        ));
    }

    @GetMapping("/summary")
    @X402Paywall(
            priceUsdc = "0.005",
            requiredQueryParams = {"rcptNo"},
            description = "AI-generated English summary of a Korean DART disclosure"
    )
    @Operation(
            summary = "Get an AI-generated English summary of a DART disclosure",
            description = """
                    Costs **0.005 USDC** on Base, settled via x402. Returns a
                    paraphrased English summary, a 1–10 importance score,
                    the canonical event type, and ticker / sector / audience
                    tags. The summary is generated once per receipt number
                    and served from cache thereafter — the price does not
                    change between cold and warm calls, but the LLM cost is
                    only paid by the first caller globally.

                    Receipt numbers (`rcptNo`) come from the free
                    `/v1/disclosures/recent` feed or `/v1/disclosures/by-ticker`
                    response. They are not LLM-knowable — agents must always
                    look one up before calling this endpoint.
                    """
            // x-payment-info extension is injected at runtime by
            // X402OpenApiCustomizer (see Operation summary above).
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment accepted; summary returned.",
                    content = @Content(schema = @Schema(implementation = DisclosureSummaryDto.class))),
            @ApiResponse(
                    responseCode = "402",
                    description = "Payment required — body carries the x402 `accepts` block with wallet, network, amount, and expiry.",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(
                    responseCode = "404",
                    description = "Unknown receipt number — the DART filing has not been ingested yet.",
                    content = @Content)
    })
    public ResponseEntity<DisclosureSummaryDto> getSummary(
            @Parameter(
                    description = "14-digit DART receipt number, e.g. `20260424900874`. " +
                            "Obtain from `/v1/disclosures/recent` or `/v1/disclosures/by-ticker`.",
                    example = "20260424900874",
                    required = true
            )
            @RequestParam("rcptNo")
            @NotBlank
            @Pattern(regexp = "^[0-9]{14}$",
                    message = "rcptNo must be exactly 14 digits")
            String rcptNo) {
        return summaryRepository.findById(rcptNo)
                .map(DisclosureSummaryDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_DISCLOSURE_SUMMARY_IMPORT = DisclosureSummary.class;
}
