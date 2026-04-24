package com.dartintel.api.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body of {@code GET /v1/pricing} — public, free, machine-readable
 * description of every paid endpoint the API exposes. Third-party discovery
 * tooling (x402scan, Bazaar, SDKs) can scrape this to build integrations
 * without reading OpenAPI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PricingResponse(
        String x402Network,
        String x402Asset,
        String x402Recipient,
        List<PaidEndpoint> endpoints
) {

    public record PaidEndpoint(
            String method,
            String path,
            String priceUsdc,
            String description
    ) {
    }
}
