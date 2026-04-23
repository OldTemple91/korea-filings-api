package com.dartintel.api.summarization.llm;

import com.dartintel.api.summarization.DisclosureContext;
import com.dartintel.api.summarization.SummaryResult;
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

        SummaryResult result = client.summarize(ctx);

        System.out.println("=== Live Gemini summary ===");
        System.out.println("summaryEn       : " + result.summaryEn());
        System.out.println("importanceScore : " + result.importanceScore());
        System.out.println("eventType       : " + result.eventType());
        System.out.println("sectorTags      : " + result.sectorTags());
        System.out.println("tickerTags      : " + result.tickerTags());
        System.out.println("actionableFor   : " + result.actionableFor());

        assertThat(result.summaryEn()).isNotBlank();
        assertThat(result.summaryEn().length()).isLessThanOrEqualTo(400);
        assertThat(result.importanceScore()).isBetween(1, 10);
        assertThat(result.eventType()).isNotBlank();
        assertThat(result.sectorTags()).isNotNull();
        assertThat(result.tickerTags()).isNotNull();
        assertThat(result.actionableFor()).isNotNull();
    }
}
