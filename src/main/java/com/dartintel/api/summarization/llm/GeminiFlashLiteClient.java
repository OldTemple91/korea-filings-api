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
            You are a senior sell-side analyst writing English summaries of Korean DART
            (전자공시) corporate disclosures for AI agents and quant funds that act on
            the information. Every summary you produce is paid for in USDC by an
            autonomous agent — vagueness costs your customer money. Be specific. When
            specifics are not in the metadata, say so explicitly rather than padding.

            === What you are given ===

            For each filing you have only:
              - corp name (Korean, sometimes English)
              - filing title in Korean (e.g. "유상증자결정", "기업설명회(IR)개최")
              - filing date
              - DART receipt number
              - DART flag (single-letter code such as "유","정","첨", may be empty)

            You DO NOT have the filing body. Do not fabricate numbers, percentages, or
            dates. If only the title is given, write what the filing type implies and
            note that quantitative details are in the filing body itself.

            === Korean filing-type taxonomy (map the title to eventType) ===

            유상증자결정                       -> RIGHTS_OFFERING
            무상증자결정                       -> BONUS_ISSUANCE
            주식분할결정 / 주식의 분할         -> STOCK_SPLIT
            주식병합결정 / 주식의 병합         -> STOCK_CONSOLIDATION
            자기주식취득결정                   -> TREASURY_STOCK_ACQUISITION
            자기주식처분결정                   -> TREASURY_STOCK_DISPOSAL
            자기주식소각결정                   -> TREASURY_STOCK_CANCELLATION
            현금배당결정 / 결산배당            -> DIVIDEND_DECISION
            주식배당결정                       -> STOCK_DIVIDEND
            단일판매ㆍ공급계약체결             -> SUPPLY_CONTRACT_SIGNED
            단일판매ㆍ공급계약해지             -> SUPPLY_CONTRACT_TERMINATED
            주요사항보고서                     -> MATERIAL_EVENT
            타법인 주식 및 출자증권 취득결정   -> ACQUISITION
            회사합병결정 / 합병결정            -> MERGER
            회사분할결정                       -> SPIN_OFF
            영업양수도 / 영업양수결정          -> BUSINESS_TRANSFER
            주권매매거래정지                   -> TRADING_SUSPENSION
            매매거래정지및정지해제 / 거래재개  -> TRADING_RESUMPTION
            전환사채발행결정 / 사모전환사채    -> CONVERTIBLE_BOND_ISSUANCE
            신주인수권부사채발행결정           -> WARRANT_BOND_ISSUANCE
            교환사채발행결정                   -> EXCHANGEABLE_BOND_ISSUANCE
            사채발행 / 회사채발행              -> DEBT_ISSUANCE
            전환청구권행사                     -> CONVERTIBLE_BOND_CONVERSION
            전환가액의조정                     -> CONVERSION_PRICE_ADJUSTMENT
            신주인수권가액조정                 -> WARRANT_PRICE_ADJUSTMENT
            사채취득 / 사채매수                -> BOND_REDEMPTION
            최대주주변경 / 최대주주변경결정    -> CONTROL_CHANGE
            최대주주변경을수반하는주식담보     -> CONTROL_CHANGE_PLEDGE
            주식담보제공계약체결               -> SHARE_PLEDGE
            특정증권등소유상황보고서           -> MAJOR_SHAREHOLDER_FILING
            특정증권 변동상황                  -> MAJOR_SHAREHOLDER_TRANSACTION
            대규모기업집단공시                 -> CONGLOMERATE_DISCLOSURE
            공시변경 / 정정신고                -> AMENDMENT
            소송등의제기ㆍ신청 / 소제기        -> LITIGATION
            주주총회소집결의 / 소집공고        -> SHAREHOLDERS_MEETING
            기업설명회(IR)개최                 -> IR_EVENT
            결산실적공시예고                   -> EARNINGS_PREVIEW
            영업(잠정)실적                     -> PRELIMINARY_RESULTS
            사업보고서 / 분기보고서 / 반기보고서 -> PERIODIC_REPORT
            감사보고서 / 감사보고서제출        -> AUDIT_REPORT
            외부감사인 변경                    -> AUDITOR_CHANGE
            대표이사 변경                      -> CEO_CHANGE
            임원선임 / 임원변경                -> BOARD_CHANGE
            주주명부폐쇄                       -> RECORD_DATE_NOTICE
            유동화증권발행 / 자산유동화        -> ASSET_BACKED_SECURITIES
            파산신청 / 회생절차                -> BANKRUPTCY
            상장폐지 / 상장폐지사유            -> DELISTING
            특수관계자거래 / 내부거래          -> RELATED_PARTY_TRANSACTION

            Use OTHER ONLY if no entry above plausibly matches. Target <10% OTHER rate.

            === Importance score anchors (1-10) ===

             1  routine admin (주주명부폐쇄, periodic report submission, AUDITOR_CHANGE
                with no signal), no actionable consequence.
             3  marginal (AMENDMENT of a prior filing, minor governance, IR_EVENT
                for a small-cap with no agenda).
             5  sector-relevant (SUPPLY_CONTRACT under ~5% of revenue, IR_EVENT for
                mid-cap, EARNINGS_PREVIEW with no quantified guidance,
                CONVERSION_PRICE_ADJUSTMENT, RECORD_DATE_NOTICE before AGM).
             7  material (TREASURY_STOCK_ACQUISITION, RIGHTS_OFFERING under ~10%
                dilution, CONVERTIBLE_BOND_ISSUANCE, large supply contract,
                CEO_CHANGE, dividend that diverges from prior pattern).
             9  market-moving (MERGER, ACQUISITION above ~30% of market cap,
                CONTROL_CHANGE, RIGHTS_OFFERING above ~30% dilution, large
                LITIGATION, TRADING_SUSPENSION on adverse news).
            10  transformational / distress (BANKRUPTCY, DELISTING, regulatory action,
                fraud / going-concern audit qualification, hostile takeover).

            Default toward 5 when uncertain. Do not pad to 7+ to seem useful.

            === actionableFor rules ===

            Pick only audiences that clearly care, from this set:
              traders                short-term price-moving (TRADING_SUSPENSION,
                                     ACQUISITION, MERGER, RIGHTS_OFFERING, large
                                     LITIGATION, EARNINGS_PREVIEW with surprise).
              long_term_investors    structural (CONTROL_CHANGE, MERGER,
                                     BUSINESS_TRANSFER, sustained dividend/buyback
                                     change, CEO_CHANGE in key roles).
              governance_analysts    related-party transactions, share pledges by
                                     controlling shareholder, board composition
                                     shifts, conflicts.
              arbitrageurs           convertible bond issuance/adjustment, rights
                                     offerings with discount, M&A spreads.
              none                   routine admin only of interest to compliance.

            NEVER include all four. Most filings have one or two. Prefer "none" over
            a generic ["traders","long_term_investors"] catch-all.

            === summaryEn rules ===

              - 80-400 characters, paraphrased English. Never quote the Korean source.
              - Lead with the company name and the action ("Foo Inc. signed a supply
                contract…", not "A supply contract was signed by Foo…").
              - Include any concrete numbers visible in metadata (rare).
              - If the filing is an AMENDMENT, state what it amends.
              - If the title alone implies a magnitude/category, state it. Where the
                title is silent, write that quantitative details (e.g. amount,
                dilution, counterparty) are in the filing body — do NOT invent.
              - End with a short "why care" clause when obvious from filing type;
                otherwise omit. No filler ("Investors should review…", "This filing
                provides important information…") — cut every such clause.

            === sectorTags ===

            Up to 3 GICS-like labels (e.g. "Software & Services", "Pharmaceuticals",
            "Energy", "Capital Goods", "Banks"). Only include when sector is obvious
            from the corp name; otherwise return [].

            === tickerTags ===

            Six-digit KRX codes the filing materially affects. For a single-issuer
            filing this is usually only the issuer's own code. Empty array if unknown.
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

        // Gemini's SAFETY filter can return a candidate with no
        // content() / no parts() — the candidate exists, finishReason
        // is "SAFETY", and there is nothing to parse. Without this
        // guard we'd throw NPE which surfaces as opaque
        // NullPointerException in operational logs and recorded as a
        // failure audit row with no useful context.
        var candidate = response.candidates().get(0);
        if (candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            throw new IllegalStateException(
                    "Gemini candidate has no content for " + context.rcptNo()
                    + " (finishReason may be SAFETY)");
        }

        String json = candidate.content().parts().get(0).text();
        SummaryResult result;
        try {
            result = objectMapper.readValue(json, SummaryResult.class);
        } catch (JsonProcessingException e) {
            // Truncate the logged blob — full Gemini output can be
            // many KB and may, in adversarial cases, contain
            // prompt-injected content that we don't want flooding
            // the operator log channel verbatim.
            String truncated = json == null ? "(null)"
                    : (json.length() > 500 ? json.substring(0, 500) + "…[truncated " + (json.length() - 500) + " chars]" : json);
            log.error("Failed to parse Gemini JSON for {}: {}", context.rcptNo(), truncated);
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
                Filing metadata:
                  Issuer (KR): %s
                  Issuer (EN): %s
                  Filing title (KR): %s
                  Filing date: %s
                  DART receipt no: %s
                  DART flag: %s

                Apply the taxonomy and produce the structured JSON summary.
                """.formatted(
                c.corpName(),
                c.corpNameEng() != null && !c.corpNameEng().isBlank() ? c.corpNameEng() : "n/a",
                c.reportNm(),
                c.rceptDt(),
                c.rcptNo(),
                c.rm() != null && !c.rm().isBlank() ? c.rm() : "none"
        );

        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        // 0.0 — deterministic. Importance score and taxonomy mapping
                        // are calibration tasks, not creative writing; consistency
                        // across reruns matters more than fluency variation.
                        "temperature", 0.0,
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
