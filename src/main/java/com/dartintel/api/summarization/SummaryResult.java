package com.dartintel.api.summarization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SummaryResult(
        String summaryEn,
        int importanceScore,
        List<String> sectorTags,
        List<String> tickerTags,
        String eventType,
        List<String> actionableFor
) {
}
