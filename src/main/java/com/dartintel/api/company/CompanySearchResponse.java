package com.dartintel.api.company;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response shape for {@code GET /v1/companies?q=...}. Replaces the
 * earlier untyped {@code Map<String, Object>} envelope so the
 * generated OpenAPI schema documents what the agent gets back — most
 * importantly the {@code isExactMatch} flag that lets a fuzzy search
 * caller pick the canonical hit out of multiple matches.
 */
public record CompanySearchResponse(
        @Schema(description = "Matching companies in trigram-relevance order, " +
                "newest first. {@code isExactMatch=true} flags the canonical " +
                "result when the query exactly matches a ticker / Korean name / " +
                "English name (case-insensitive).")
        List<CompanyDto> matches
) {
}
