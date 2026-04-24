package com.dartintel.api.api;

import com.dartintel.api.api.dto.DisclosureSummaryDto;
import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.summarization.DisclosureSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/disclosures")
@RequiredArgsConstructor
public class DisclosuresController {

    private final DisclosureSummaryRepository summaryRepository;

    @GetMapping("/{rcptNo}/summary")
    @X402Paywall(priceUsdc = "0.005", description = "AI-generated English summary of a Korean DART disclosure")
    public ResponseEntity<DisclosureSummaryDto> getSummary(@PathVariable String rcptNo) {
        return summaryRepository.findById(rcptNo)
                .map(DisclosureSummaryDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
