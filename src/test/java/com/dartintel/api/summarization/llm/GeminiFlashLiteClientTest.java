package com.dartintel.api.summarization.llm;

import com.dartintel.api.summarization.DisclosureContext;
import com.dartintel.api.summarization.SummaryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiFlashLiteClientTest {

    private static final String GEN_PATH = "/v1beta/models/gemini-2.5-flash-lite:generateContent";

    private static WireMockServer wireMock;
    private GeminiFlashLiteClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        GeminiProperties props = new GeminiProperties(
                "http://localhost:" + wireMock.port(),
                "test-key",
                new GeminiProperties.Timeout(2000, 5000)
        );
        client = new GeminiFlashLiteClient(WebClient.builder(), props, new ObjectMapper());
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void parsesStructuredJsonReturnedByGemini() {
        wireMock.stubFor(post(urlPathEqualTo(GEN_PATH))
                .withQueryParam("key", equalTo("test-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{
                                        "text": "{\\"summaryEn\\":\\"Samsung announced a rights offering decision on April 23, 2026.\\",\\"importanceScore\\":7,\\"sectorTags\\":[\\"Information Technology\\"],\\"tickerTags\\":[\\"005930\\"],\\"eventType\\":\\"RIGHTS_OFFERING\\",\\"actionableFor\\":[\\"traders\\",\\"long_term_investors\\"]}"
                                      }],
                                      "role": "model"
                                    },
                                    "finishReason": "STOP"
                                  }],
                                  "usageMetadata": {
                                    "promptTokenCount": 142,
                                    "candidatesTokenCount": 89,
                                    "totalTokenCount": 231
                                  }
                                }
                                """)));

        DisclosureContext ctx = new DisclosureContext(
                "20260423000001", "00126380", "삼성전자", "Samsung Electronics Co., Ltd.",
                "주요사항보고서(유상증자결정)", LocalDate.of(2026, 4, 23), "유"
        );

        SummaryEnvelope envelope = client.summarize(ctx);

        assertThat(envelope.result().summaryEn()).contains("rights offering");
        assertThat(envelope.result().importanceScore()).isEqualTo(7);
        assertThat(envelope.result().eventType()).isEqualTo("RIGHTS_OFFERING");
        assertThat(envelope.result().sectorTags()).containsExactly("Information Technology");
        assertThat(envelope.result().tickerTags()).containsExactly("005930");
        assertThat(envelope.result().actionableFor()).containsExactly("traders", "long_term_investors");
        assertThat(envelope.model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(envelope.inputTokens()).isEqualTo(142);
        assertThat(envelope.outputTokens()).isEqualTo(89);
        // 142*0.10 + 89*0.40 = 49.8 USD per 1M tokens -> 0.0000498 USD per call
        assertThat(envelope.costUsd()).isEqualByComparingTo(new BigDecimal("0.00004980"));
        assertThat(envelope.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sendsRequestWithApiKeyDisclosureMetadataAndResponseSchema() {
        stubMinimalSuccess();

        DisclosureContext ctx = new DisclosureContext(
                "20260423000001", "00126380", "삼성전자", null,
                "주요사항보고서(유상증자결정)", LocalDate.of(2026, 4, 23), null
        );

        client.summarize(ctx);

        wireMock.verify(postRequestedFor(urlPathEqualTo(GEN_PATH))
                .withQueryParam("key", equalTo("test-key"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("삼성전자"))
                .withRequestBody(containing("주요사항보고서(유상증자결정)"))
                .withRequestBody(containing("20260423000001"))
                .withRequestBody(containing("\"responseMimeType\":\"application/json\""))
                .withRequestBody(containing("\"responseSchema\""))
                .withRequestBody(containing("\"systemInstruction\""))
        );
    }

    @Test
    void throwsWhenGeminiReturnsNoCandidates() {
        wireMock.stubFor(post(urlPathEqualTo(GEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"candidates\":[]}")));

        DisclosureContext ctx = anyContext();

        assertThatThrownBy(() -> client.summarize(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no candidates");
    }

    @Test
    void throwsWhenGeminiCandidateContainsNonJsonText() {
        wireMock.stubFor(post(urlPathEqualTo(GEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"candidates":[{"content":{"parts":[{"text":"not-json-text"}],"role":"model"}}]}
                                """)));

        DisclosureContext ctx = anyContext();

        assertThatThrownBy(() -> client.summarize(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid JSON");
    }

    private static DisclosureContext anyContext() {
        return new DisclosureContext(
                "20260423000001", "00126380", "삼성전자", null,
                "x", LocalDate.of(2026, 4, 23), null
        );
    }

    private static void stubMinimalSuccess() {
        wireMock.stubFor(post(urlPathEqualTo(GEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"candidates":[{"content":{"parts":[{"text":"{\\"summaryEn\\":\\"x\\",\\"importanceScore\\":1,\\"sectorTags\\":[],\\"tickerTags\\":[],\\"eventType\\":\\"OTHER\\",\\"actionableFor\\":[]}"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}
                                """)));
    }
}
