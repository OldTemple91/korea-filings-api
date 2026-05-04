package com.dartintel.api.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Response body of {@code GET /v1/pricing} — public, free, machine-readable
 * description of every paid endpoint the API exposes. Third-party discovery
 * tooling (x402scan, Bazaar, SDKs) can scrape this to build integrations
 * without reading OpenAPI.
 *
 * <p>The {@code paymentHeaders} block tells callers exactly which HTTP
 * header to send and how to interpret the settlement response — useful
 * because the x402 ecosystem currently has v1 ({@code X-PAYMENT}) and
 * v2 ({@code PAYMENT-SIGNATURE}) clients in the wild and most listings
 * do not say which they accept.
 *
 * <p>The {@code workflow} block sketches the canonical free-then-paid
 * call sequence so a cold-start agent can compose calls without the
 * human-readable docs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PricingResponse(
        String x402Network,
        String x402Asset,
        String x402Recipient,
        PaymentHeaders paymentHeaders,
        Workflow workflow,
        List<PaidEndpoint> endpoints
) {

    public record PaidEndpoint(
            String method,
            String path,
            String priceUsdc,
            String pricingMode,
            String description,
            List<RequiredParam> requiredParams,
            String exampleCall
    ) {
    }

    public record RequiredParam(
            String name,
            String in,         // "query" | "header"
            String type,       // "string" | "integer"
            boolean required,
            String description,
            String example
    ) {
    }

    /**
     * x402 transport header convention. {@code preferred} is what new
     * clients should send; {@code accepted} is the full list including
     * v1 legacy aliases for older SDK / MCP releases.
     */
    public record PaymentHeaders(
            String preferred,
            List<String> accepted,
            String challenge,
            String settlement,
            List<String> settlementAliases,
            String spec
    ) {
    }

    public record Workflow(
            List<String> steps,
            Map<String, String> freeEndpoints
    ) {
    }
}
