package com.dartintel.api.api;

import com.dartintel.api.api.dto.DisclosureSummaryDto;
import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.summarization.DisclosureSummaryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/disclosures")
@RequiredArgsConstructor
@Tag(name = "Disclosures", description = "Paid DART disclosure intelligence endpoints.")
public class DisclosuresController {

    private final DisclosureSummaryRepository summaryRepository;

    @GetMapping("/{rcptNo}/summary")
    @X402Paywall(priceUsdc = "0.005", description = "AI-generated English summary of a Korean DART disclosure")
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
                    """
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
                    description = "14-digit DART receipt number, e.g. `20260424900874`",
                    example = "20260424900874",
                    required = true
            )
            @PathVariable String rcptNo) {
        return summaryRepository.findById(rcptNo)
                .map(DisclosureSummaryDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
