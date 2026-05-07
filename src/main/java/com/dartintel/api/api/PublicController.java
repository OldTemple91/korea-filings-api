package com.dartintel.api.api;

import com.dartintel.api.api.dto.PricingResponse;
import com.dartintel.api.api.dto.SampleResponses;
import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.payment.X402Properties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, unpaid endpoints. Lives at {@code /v1} so the x402 paywall
 * interceptor sees the handlers (they simply have no {@link X402Paywall}
 * annotation, so the interceptor short-circuits).
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Public", description = "Free, unpaid endpoints for service discovery and pricing.")
public class PublicController {

    private final X402Properties x402Properties;
    private final RequestMappingHandlerMapping handlerMapping;
    /**
     * Pre-built pricing response cached at boot. The set of paid
     * endpoints, their prices, and the wallet config are all
     * configuration-time constants — there's no need to reflect over
     * the handler mapping on every {@code /v1/pricing} hit (this
     * endpoint is publicly free, frequently crawled by indexers, and
     * was the hottest free-endpoint allocation source).
     */
    private volatile PricingResponse cachedPricing;

    public PublicController(
            X402Properties x402Properties,
            // Spring Boot also registers a "controllerEndpointHandlerMapping" for actuator;
            // we only want the MVC-controller one here.
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.x402Properties = x402Properties;
        this.handlerMapping = handlerMapping;
    }

    @PostConstruct
    void buildPricingCache() {
        List<PricingResponse.PaidEndpoint> paid = handlerMapping.getHandlerMethods().entrySet()
                .stream()
                .filter(e -> e.getValue().hasMethodAnnotation(X402Paywall.class))
                .map(PublicController::toPaidEndpoint)
                .sorted(Comparator.comparing(PricingResponse.PaidEndpoint::path)
                        .thenComparing(PricingResponse.PaidEndpoint::method))
                .toList();
        this.cachedPricing = new PricingResponse(
                x402Properties.network(),
                x402Properties.asset(),
                x402Properties.recipientAddress(),
                buildPaymentHeaders(),
                buildWorkflow(),
                paid
        );
    }

    @GetMapping("/pricing")
    @SecurityRequirements // override global PAYMENT-SIGNATURE requirement — this endpoint is free.
    @Operation(
            summary = "Current pricing and x402 wallet configuration",
            description = """
                    Machine-readable descriptor of every paid endpoint and
                    its USDC price. Also carries the x402 recipient wallet,
                    CAIP-2 network id, USDC contract address, the canonical
                    payment header convention (PAYMENT-SIGNATURE in, the
                    PAYMENT-RESPONSE alongside its legacy alias on the way
                    out), and the free→paid workflow steps so a cold-start
                    agent can compose calls without reading prose docs.

                    Safe to call as often as desired — no payment, no rate
                    limiting beyond the standard anti-abuse layer.
                    """,
            responses = @ApiResponse(
                    responseCode = "200",
                    content = @Content(schema = @Schema(implementation = PricingResponse.class)))
    )
    public ResponseEntity<PricingResponse> pricing() {
        return ResponseEntity.ok(cachedPricing);
    }

    /**
     * Document the x402 transport header convention so clients do not
     * have to guess between v1 ({@code X-PAYMENT}) and v2
     * ({@code PAYMENT-SIGNATURE}). New integrations send the v2 name;
     * the server still accepts the v1 alias for older SDK / MCP
     * releases.
     */
    private static PricingResponse.PaymentHeaders buildPaymentHeaders() {
        return new PricingResponse.PaymentHeaders(
                "PAYMENT-SIGNATURE",
                List.of("PAYMENT-SIGNATURE", "X-PAYMENT"),
                "PAYMENT-REQUIRED",
                "PAYMENT-RESPONSE",
                List.of("X-PAYMENT-RESPONSE"),
                "https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md"
        );
    }

    /**
     * Sketch the canonical free→paid call sequence so an agent that
     * starts from a plain Korean company name can reach a paid summary
     * without help from prose docs. Two free hops resolve a name to
     * either a ticker or a receipt number; the paid hop returns the
     * AI summary.
     */
    private static PricingResponse.Workflow buildWorkflow() {
        Map<String, String> freeEndpoints = new LinkedHashMap<>();
        freeEndpoints.put(
                "GET /v1/companies?q={name}",
                "Trigram fuzzy search across KRX-listed companies. " +
                        "Returns ticker + corp_code, free.");
        freeEndpoints.put(
                "GET /v1/disclosures/recent?limit={n}&since_hours={h}",
                "Market-wide metadata feed of the most recent DART filings. " +
                        "Use to discover rcptNo values without paying.");
        return new PricingResponse.Workflow(
                List.of(
                        "1. (free) Resolve a Korean company name to a ticker via " +
                                "GET /v1/companies?q={name}.",
                        "2. (paid) Get summaries for that ticker via " +
                                "GET /v1/disclosures/by-ticker?ticker={ticker}&limit={n}. " +
                                "Costs 0.005 USDC × n.",
                        "3. (paid, optional) Re-fetch a single summary by receipt " +
                                "number via GET /v1/disclosures/summary?rcptNo={rcptNo}. " +
                                "Costs 0.005 USDC. The receipt number is in the response " +
                                "of step 2 or in the free /v1/disclosures/recent feed."
                ),
                freeEndpoints
        );
    }

    private static PricingResponse.PaidEndpoint toPaidEndpoint(
            Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        HandlerMethod handler = entry.getValue();
        X402Paywall annotation = handler.getMethodAnnotation(X402Paywall.class);
        RequestMappingInfo info = entry.getKey();

        String path = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString()
                : info.getPatternValues().iterator().next();
        String method = info.getMethodsCondition().getMethods().isEmpty()
                ? "GET"
                : info.getMethodsCondition().getMethods().iterator().next().name();

        return new PricingResponse.PaidEndpoint(
                method,
                path,
                annotation.priceUsdc(),
                annotation.pricingMode().name().toLowerCase(),
                annotation.description(),
                describeRequiredParams(path),
                describeExampleCall(path, annotation),
                // Per-endpoint sample matching the actual top-level
                // response shape: by-ticker returns ByTickerResponse
                // (envelope with ticker / chargedFor / delivered /
                // summaries), summary returns DisclosureSummaryDto
                // directly. Round-12.1 fix — round-12 originally used
                // the per-row shape on both endpoints, which made the
                // /v1/pricing contract diverge from reality for the
                // batch endpoint. Agents auto-parsing the spec now see
                // exactly what `JSON.parse(response.body)` yields.
                describeSampleResponse(path),
                SampleResponses.SAMPLE_SETTLEMENT_TX_URL
        );
    }

    /**
     * Pick the per-endpoint sample shape. Annotated paths drive the
     * dispatch so a future paid endpoint that ships its own envelope
     * type only needs an entry here, not a fallback through reflection.
     */
    private static Object describeSampleResponse(String path) {
        return switch (path) {
            case "/v1/disclosures/by-ticker" -> SampleResponses.sampleByTickerResponse();
            case "/v1/disclosures/summary"   -> SampleResponses.sampleSummary();
            default -> SampleResponses.sampleSummary();
        };
    }

    /**
     * Hand-curated required-param descriptors for each paid endpoint
     * path. The MVC mapping info itself doesn't carry param-level
     * documentation in a form that can be turned into a public
     * descriptor without reflective gymnastics; centralising the table
     * here is cheaper than reflecting controller method signatures and
     * keeps the wire shape stable.
     */
    private static List<PricingResponse.RequiredParam> describeRequiredParams(String path) {
        return switch (path) {
            case "/v1/disclosures/by-ticker" -> List.of(
                    new PricingResponse.RequiredParam(
                            "ticker", "query", "string", true,
                            "Six-digit KRX ticker. Resolve from a name via " +
                                    "GET /v1/companies?q={name}.",
                            "005930"),
                    new PricingResponse.RequiredParam(
                            "limit", "query", "integer", false,
                            "Max filings to return (1–50, default 5). " +
                                    "Final price is 0.005 × limit USDC.",
                            "5")
            );
            case "/v1/disclosures/summary" -> List.of(
                    new PricingResponse.RequiredParam(
                            "rcptNo", "query", "string", true,
                            "14-digit DART receipt number. Obtain from " +
                                    "GET /v1/disclosures/recent or from the by-ticker response.",
                            "20260424900874")
            );
            default -> List.of();
        };
    }

    private static String describeExampleCall(String path, X402Paywall annotation) {
        return switch (path) {
            case "/v1/disclosures/by-ticker" ->
                    "GET /v1/disclosures/by-ticker?ticker=005930&limit=3";
            case "/v1/disclosures/summary" ->
                    "GET /v1/disclosures/summary?rcptNo=20260424900874";
            default -> "GET " + path;
        };
    }
}
