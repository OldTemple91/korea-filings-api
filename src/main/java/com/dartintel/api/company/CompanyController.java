package com.dartintel.api.company;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Free company-directory endpoints.
 *
 * <p>The two routes here are the agent's first contact with this
 * service. Their job is to translate whatever identifier an agent or
 * user provides — a Korean name, an English name, a partial substring,
 * or a six-digit ticker — into the canonical {@code ticker} that the
 * paid {@code /v1/disclosures/by-ticker?ticker=...} endpoint expects.
 *
 * <p>Both routes are unauthenticated and not paywalled. The values
 * they return (ticker, corp_code, names, market) are public on the
 * KRX and DART websites, so paywalling discovery would only frustrate
 * the agent flow without protecting any proprietary data.
 */
@RestController
@RequestMapping("/v1/companies")
@RequiredArgsConstructor
@Tag(name = "Companies", description = "Free directory of Korean listed companies — search by name or ticker.")
public class CompanyController {

    private final CompanyService service;

    @GetMapping
    @SecurityRequirements // unauthenticated
    @Operation(
            summary = "Search Korean listed companies by name or ticker",
            description = """
                    Searches the KRX listed-company directory by Korean name,
                    English name, or six-digit ticker. Trigram fuzzy match —
                    the closest hits come first. Use this as the first step
                    before calling the paid {@code by-ticker} endpoint when
                    you only have a company name.
                    """,
            responses = @ApiResponse(
                    responseCode = "200",
                    content = @Content(schema = @Schema(implementation = CompanyDto.class)))
    )
    public ResponseEntity<Map<String, Object>> search(
            @Parameter(
                    description = "Free-text query: company name (Korean or English) or six-digit ticker.",
                    example = "Samsung Electronics",
                    required = true
            )
            @RequestParam("q") String q,
            @Parameter(description = "Max matches to return (1-50, default 20).")
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        List<CompanyDto> matches = service.search(q, limit).stream()
                .map(CompanyDto::from)
                .toList();
        return ResponseEntity.ok(Map.of("matches", matches));
    }

    @GetMapping("/{ticker}")
    @SecurityRequirements // unauthenticated
    @Operation(
            summary = "Get a Korean listed company by ticker",
            description = """
                    Looks up a company by its six-digit KRX ticker. Returns
                    {@code 404} for tickers we have no record of (delisted,
                    not yet synced, or simply invalid). Useful as a
                    confirmation step after a fuzzy search.
                    """
    )
    public ResponseEntity<CompanyDto> getByTicker(
            @Parameter(description = "Six-digit KRX ticker, e.g. \"005930\" for Samsung Electronics.",
                    example = "005930", required = true)
            @PathVariable String ticker
    ) {
        return service.findByTicker(ticker)
                .map(CompanyDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
