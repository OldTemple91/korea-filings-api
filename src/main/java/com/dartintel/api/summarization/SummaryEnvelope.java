package com.dartintel.api.summarization;

import java.math.BigDecimal;

public record SummaryEnvelope(
        SummaryResult result,
        String model,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        long latencyMs
) {
}
