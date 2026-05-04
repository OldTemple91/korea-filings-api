package com.dartintel.api.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response shape for {@code GET /v1/disclosures/recent}. Replaces the
 * earlier untyped {@code Map<String, Object>} envelope so the
 * generated OpenAPI schema actually documents the field names — SDK
 * codegen and tool-using agents both rely on the OpenAPI declaration.
 */
public record RecentFilingsResponse(
        @Schema(description = "Recent DART filings, newest first.")
        List<RecentFilingDto> filings
) {
}
