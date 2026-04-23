package com.dartintel.api.summarization.llm;

import com.dartintel.api.summarization.DisclosureContext;
import com.dartintel.api.summarization.SummaryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = "AIza.+")
class GeminiFlashLiteClientLiveIT {

    @Test
    void summarizesARealKoreanDisclosureAgainstLiveGemini() {
        String key = System.getenv("GEMINI_API_KEY");
        GeminiProperties props = new GeminiProperties(
                "https://generativelanguage.googleapis.com",
                key,
                new GeminiProperties.Timeout(5000, 30000)
        );
        GeminiFlashLiteClient client = new GeminiFlashLiteClient(
                WebClient.builder(), props, new ObjectMapper());

        DisclosureContext ctx = new DisclosureContext(
                "20260423000001",
                "00126380",
                "삼성전자",
                "Samsung Electronics Co., Ltd.",
                "주요사항보고서(유상증자결정)",
                LocalDate.of(2026, 4, 23),
                "유"
        );

        SummaryEnvelope envelope = client.summarize(ctx);

        System.out.println("=== Live Gemini summary ===");
        System.out.println("summaryEn       : " + envelope.result().summaryEn());
        System.out.println("importanceScore : " + envelope.result().importanceScore());
        System.out.println("eventType       : " + envelope.result().eventType());
        System.out.println("sectorTags      : " + envelope.result().sectorTags());
        System.out.println("tickerTags      : " + envelope.result().tickerTags());
        System.out.println("actionableFor   : " + envelope.result().actionableFor());
        System.out.println("model           : " + envelope.model());
        System.out.println("inputTokens     : " + envelope.inputTokens());
        System.out.println("outputTokens    : " + envelope.outputTokens());
        System.out.println("costUsd         : $" + envelope.costUsd());
        System.out.println("latencyMs       : " + envelope.latencyMs() + " ms");

        assertThat(envelope.result().summaryEn()).isNotBlank();
        assertThat(envelope.result().summaryEn().length()).isLessThanOrEqualTo(400);
        assertThat(envelope.result().importanceScore()).isBetween(1, 10);
        assertThat(envelope.result().eventType()).isNotBlank();
        assertThat(envelope.result().sectorTags()).isNotNull();
        assertThat(envelope.result().tickerTags()).isNotNull();
        assertThat(envelope.result().actionableFor()).isNotNull();
        assertThat(envelope.model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(envelope.inputTokens()).isPositive();
        assertThat(envelope.outputTokens()).isPositive();
        assertThat(envelope.costUsd()).isNotNull();
        assertThat(envelope.latencyMs()).isPositive();
    }
}
