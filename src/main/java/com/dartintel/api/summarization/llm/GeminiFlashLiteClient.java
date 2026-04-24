package com.dartintel.api.summarization.llm;

import com.dartintel.api.summarization.DisclosureContext;
import com.dartintel.api.summarization.SummaryEnvelope;
import com.dartintel.api.summarization.SummaryResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class GeminiFlashLiteClient implements LlmClient {

    static final String MODEL = "gemini-2.5-flash-lite";

    // Gemini 2.5 Flash-Lite published price (USD per 1M tokens)
    private static final BigDecimal INPUT_PRICE_PER_1M = new BigDecimal("0.10");
    private static final BigDecimal OUTPUT_PRICE_PER_1M = new BigDecimal("0.40");
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private static final String SYSTEM_PROMPT = """
            You are a financial analyst summarising Korean corporate disclosures filed
            with DART (Financial Supervisory Service) into structured English data for
            AI agents and quantitative funds.

            Rules:
            - summaryEn: paraphrased English (NEVER quote the Korean source verbatim),
              max 400 characters, concrete and self-contained.
            - importanceScore: 1 (housekeeping), 5 (sector watchers should note),
              10 (market-moving for the issuer).
            - eventType: a single canonical UPPER_SNAKE_CASE label such as
              RIGHTS_OFFERING, MERGER, SPIN_OFF, DIVIDEND_DECISION, BOARD_CHANGE,
              TREASURY_STOCK_ACQUISITION, IR_EVENT, AUDIT_REPORT, OTHER.
            - sectorTags: up to 3 GICS-style sector labels.
            - tickerTags: 6-digit Korean stock codes that the filing materially affects.
            - actionableFor: subset of ["traders","long_term_investors","governance_analysts","none"].
            """;

    private final WebClient webClient;
    private final String apiKey;
    private final Duration readTimeout;
    private final Duration blockTimeout;
    private final ObjectMapper objectMapper;

    public GeminiFlashLiteClient(
            WebClient.Builder builder,
            GeminiProperties props,
            ObjectMapper objectMapper
    ) {
        GeminiProperties.Timeout timeout = props.timeout();
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeout.connectMs()))
                .build();
        this.webClient = builder.clone()
                .baseUrl(props.baseUrl())
                .clientConnector(new JdkClientHttpConnector(jdkHttpClient))
                .build();
        this.apiKey = props.key();
        this.readTimeout = Duration.ofMillis(timeout.readMs());
        this.blockTimeout = Duration.ofMillis(timeout.connectMs() + timeout.readMs() + 5_000L);
        this.objectMapper = objectMapper;
    }

    @Override
    public String modelId() {
        return MODEL;
    }

    @RateLimiter(name = "gemini")
    @CircuitBreaker(name = "gemini")
    @Retry(name = "gemini")
    @Override
    public SummaryEnvelope summarize(DisclosureContext context) {
        Map<String, Object> body = buildRequestBody(context);

        long startNanos = System.nanoTime();
        GeminiResponse response = webClient.post()
                .uri(uri -> uri
                        .path("/v1beta/models/" + MODEL + ":generateContent")
                        .queryParam("key", apiKey)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GeminiResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new IllegalStateException("Gemini returned no candidates for " + context.rcptNo());
        }

        String json = response.candidates().get(0).content().parts().get(0).text();
        SummaryResult result;
        try {
            result = objectMapper.readValue(json, SummaryResult.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Gemini JSON for {}: {}", context.rcptNo(), json);
            throw new IllegalStateException("Gemini produced invalid JSON for " + context.rcptNo(), e);
        }

        GeminiResponse.UsageMetadata usage = response.usageMetadata();
        int inputTokens = usage != null ? usage.promptTokenCount() : 0;
        int outputTokens = usage != null ? usage.candidatesTokenCount() : 0;
        return new SummaryEnvelope(
                result, MODEL, inputTokens, outputTokens,
                computeCost(inputTokens, outputTokens), latencyMs
        );
    }

    static BigDecimal computeCost(int inputTokens, int outputTokens) {
        BigDecimal input = INPUT_PRICE_PER_1M.multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal output = OUTPUT_PRICE_PER_1M.multiply(BigDecimal.valueOf(outputTokens));
        return input.add(output).divide(MILLION, 8, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> buildRequestBody(DisclosureContext c) {
        String userPrompt = """
                Korean disclosure metadata:
                - Company: %s%s
                - Filing type: %s
                - Filed (yyyy-MM-dd): %s
                - DART receipt no: %s
                - Disclosure flag: %s

                Produce the structured JSON summary.
                """.formatted(
                c.corpName(),
                c.corpNameEng() != null ? " (" + c.corpNameEng() + ")" : "",
                c.reportNm(),
                c.rceptDt(),
                c.rcptNo(),
                c.rm() != null ? c.rm() : "none"
        );

        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "responseMimeType", "application/json",
                        "responseSchema", buildResponseSchema()
                )
        );
    }

    private static Map<String, Object> buildResponseSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "summaryEn", Map.of("type", "STRING"),
                        "importanceScore", Map.of("type", "INTEGER"),
                        "sectorTags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "tickerTags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "eventType", Map.of("type", "STRING"),
                        "actionableFor", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of(
                        "summaryEn", "importanceScore", "eventType",
                        "sectorTags", "tickerTags", "actionableFor"
                )
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeminiResponse(List<Candidate> candidates, UsageMetadata usageMetadata) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Candidate(Content content) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Content(List<Part> parts) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Part(String text) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record UsageMetadata(int promptTokenCount, int candidatesTokenCount, int totalTokenCount) {
        }
    }
}
