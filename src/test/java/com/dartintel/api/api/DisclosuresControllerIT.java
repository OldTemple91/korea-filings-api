package com.dartintel.api.api;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.payment.PaymentLogRepository;
import com.dartintel.api.payment.dto.EvmExactPayload;
import com.dartintel.api.payment.dto.PaymentPayload;
import com.dartintel.api.payment.dto.PaymentRequirement;
import com.dartintel.api.payment.dto.ResourceInfo;
import com.dartintel.api.summarization.DisclosureSummary;
import com.dartintel.api.summarization.DisclosureSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "dart.polling.enabled=false",
        "summary.consumer.enabled=false",
        "summary.retry.enabled=false",
        "summary.backfill.enabled=false",
        "company.sync.enabled=false",
        "dart.api.key=test-dart-key",
        "gemini.key=test-gemini-key",
        "x402.recipient-address=0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
        "x402.asset=0x036CbD53842c5426634e7929541eC2318f3dCF7e",
        "x402.network=eip155:84532"
})
class DisclosuresControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static final WireMockServer wireMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        if (!wireMock.isRunning()) {
            wireMock.start();
        }
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        if (!wireMock.isRunning()) {
            wireMock.start();
        }
        r.add("x402.facilitator-url", () -> "http://localhost:" + wireMock.port());
        // Round-16 regression test points the LLM + DART clients at the
        // same WireMock so lazy summary generation is deterministic and
        // never makes a real external call. Existing tests seed real
        // summaries and so never trigger generation — unaffected.
        r.add("gemini.base-url", () -> "http://localhost:" + wireMock.port());
        r.add("dart.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DisclosureRepository disclosureRepository;

    @Autowired
    DisclosureSummaryRepository summaryRepository;

    @Autowired
    PaymentLogRepository paymentLogRepository;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        paymentLogRepository.deleteAll();
        summaryRepository.deleteAll();
        disclosureRepository.deleteAll();
        // Round-18: corpNameEng is populated (as ingestion now does) and
        // reportNm carries DART's fixed-width trailing padding, so the
        // English-surface and trim assertions exercise real shapes.
        disclosureRepository.save(new Disclosure(
                "20260423000001", "00126380", "삼성전자", "SAMSUNG ELECTRONICS CO,.LTD",
                "주요사항보고서(유상증자결정)              ", "삼성전자",
                LocalDate.of(2026, 4, 23), "유", "005930"
        ));
        summaryRepository.save(new DisclosureSummary(
                "20260423000001",
                "Samsung announced a rights offering decision.",
                9, "RIGHTS_OFFERING",
                List.of("Information Technology"), List.of("005930"),
                List.of("traders", "long_term_investors"),
                "gemini-2.5-flash-lite", 142, 89,
                new BigDecimal("0.00004980"), (short) 1
        ));
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void missingPaymentHeaderReturns402WithPaymentRequirements() throws Exception {
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001"))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("PAYMENT-REQUIRED"))
                .andExpect(jsonPath("$.x402Version").value(2))
                .andExpect(jsonPath("$.error").value("Payment required"))
                .andExpect(jsonPath("$.accepts[0].scheme").value("exact"))
                .andExpect(jsonPath("$.accepts[0].network").value("eip155:84532"))
                .andExpect(jsonPath("$.accepts[0].amount").value("5000"))
                .andExpect(jsonPath("$.accepts[0].asset")
                        .value("0x036CbD53842c5426634e7929541eC2318f3dCF7e"))
                .andExpect(jsonPath("$.accepts[0].payTo")
                        .value("0x209693Bc6afc0C5328bA36FaF03C514EF312287C"))
                .andExpect(jsonPath("$.accepts[0].extra.name").value("USDC"));
    }

    @Test
    void v2PaymentSignatureHeaderIsAcceptedAlongsideLegacyXPayment() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":true,"transaction":"0xV2","network":"eip155:84532","payer":"0x857b06519E91e3A54538791bDbb0E22373e36b66"}
                                """)));

        // Send the v2 PAYMENT-SIGNATURE header (not X-PAYMENT) — must be accepted.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", validPaymentPayloadBase64("sig-v2")))
                .andExpect(status().isOk())
                .andExpect(header().exists("PAYMENT-RESPONSE"))
                .andExpect(header().exists("X-PAYMENT-RESPONSE"))
                .andExpect(jsonPath("$.rcptNo").value("20260423000001"));

        wireMock.verify(postRequestedFor(urlPathEqualTo("/settle")));
    }

    @Test
    void settlementFailureReturns402WithPaymentResponseHeader() throws Exception {
        // Per the x402 v2 transport spec, a settle-time failure is
        // surfaced as HTTP 402 with the failure SettlementResponse
        // base64-encoded into the PAYMENT-RESPONSE header and an
        // empty JSON body. The original controller payload (the AI
        // summary) MUST NOT leak — that would be a revenue leak any
        // time the facilitator has an outage.
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"errorReason\":\"insufficient_funds\"}")));

        var result = mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", validPaymentPayloadBase64("sig-fail")))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("PAYMENT-RESPONSE"))
                .andExpect(header().exists("X-PAYMENT-RESPONSE"))
                // Empty body per v2 transport spec — settlement info is in the header.
                .andExpect(jsonPath("$.summaryEn").doesNotExist())
                .andExpect(jsonPath("$.importanceScore").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andReturn();

        // Decode the PAYMENT-RESPONSE header and confirm the failure
        // payload carries the facilitator's reason verbatim.
        String headerValue = result.getResponse().getHeader("PAYMENT-RESPONSE");
        org.assertj.core.api.Assertions.assertThat(headerValue).isNotBlank();
        byte[] decoded = java.util.Base64.getDecoder().decode(headerValue);
        java.util.Map<?, ?> proof = objectMapper.readValue(decoded, java.util.Map.class);
        org.assertj.core.api.Assertions.assertThat(proof.get("success")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(proof.get("errorReason"))
                .isEqualTo("insufficient_funds");
    }

    @Test
    void byTickerQueryParamReturnsSummariesAndCharges() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":true,"transaction":"0xT","network":"eip155:84532","payer":"0x857b06519E91e3A54538791bDbb0E22373e36b66"}
                                """)));

        String byTickerUrl = "http://localhost/v1/disclosures/by-ticker?ticker=005930&limit=3";
        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=3")
                        .header("PAYMENT-SIGNATURE",
                                validPaymentPayloadBase64("sig-bt", byTickerUrl)))
                .andExpect(status().isOk())
                .andExpect(header().exists("PAYMENT-RESPONSE"))
                .andExpect(jsonPath("$.ticker").value("005930"))
                .andExpect(jsonPath("$.summaries").isArray());
    }

    @Test
    void malformedPaymentHeaderReturns400() throws Exception {
        // x402 v2 transport spec error table: malformed payment payload
        // maps to HTTP 400, not 402. 402 is reserved for "no payment
        // provided" or "payment failed" — clients that send garbage need
        // a distinguishable code path.
        // Round-12 unified the error envelope shape: $.error is now a
        // stable code (`malformed_payment`), $.message is the human
        // string, $.agent_action_hint is the recovery instruction.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("X-PAYMENT", "not-valid-base64!@#"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_payment"))
                .andExpect(jsonPath("$.message").value(containsString("Malformed")));
    }

    @Test
    void byTickerWithoutTickerParamReturns400BeforePaywall() throws Exception {
        // Pre-paywall validation: a required query param missing from
        // the request must produce a 400 BEFORE the 402 fires. Without
        // this the agent would sign an EIP-3009 authorisation against
        // the default-count price and only then see 400 from
        // @RequestParam binding — burning a nonce for nothing.
        mockMvc.perform(get("/v1/disclosures/by-ticker?limit=3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_parameter"))
                .andExpect(jsonPath("$.message").value(containsString("ticker")));
    }

    @Test
    void summaryWithoutRcptNoReturns400BeforePaywall() throws Exception {
        mockMvc.perform(get("/v1/disclosures/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_parameter"))
                .andExpect(jsonPath("$.message").value(containsString("rcptNo")));
    }

    @Test
    void cacheControlNoStoreOnAll402And200And400() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"transaction\":\"0xCC\",\"network\":\"eip155:84532\",\"payer\":\"0x857\"}")));

        // 402 (no header)
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001"))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().string("Cache-Control", "no-store"));

        // 400 (malformed)
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));

        // 200 (paid + Vary). Spring's CORS filter sets `Vary: Origin`
        // ahead of us, and our advice appends `Vary: PAYMENT-SIGNATURE`.
        // MockMvc returns Vary as a multi-valued header — assert the
        // payment-header value is among them rather than reading just
        // the first value (which would be `Origin`).
        var result = mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", validPaymentPayloadBase64("sig-cc")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeaderValues("Vary"))
                .anySatisfy(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("PAYMENT-SIGNATURE"));
    }

    /**
     * Round-15b: the free /recent feed now LEFT-JOINs the summary
     * cache so rows whose summaries have been generated carry the
     * AI-derived classification fields ({@code importanceScore},
     * {@code eventType}, {@code sectorTags}, {@code tickerTags},
     * {@code actionableFor}) without exposing the paid summary text.
     *
     * <p>Seed has one disclosure (`20260423000001`, Samsung rights
     * offering) with a cached summary at importance 9. The unseeded
     * second filing below verifies the un-enriched fallback shape
     * stays byte-identical to the pre-15b response (AI fields are
     * absent thanks to {@code @JsonInclude(NON_NULL)} on the DTO).
     * The endpoint should also never leak {@code summaryEn}.
     */
    @Test
    void recentFeedEnrichesCachedRowsAndOmitsAiFieldsForUncached() throws Exception {
        // Second disclosure — same ingestion shape as the seeded one
        // but no corresponding `disclosure_summary` row. Represents the
        // common case of "DART filing has landed in our DB, no agent
        // has paid for the summary yet".
        disclosureRepository.save(new Disclosure(
                "20260423000002", "00164742", "에스케이하이닉스", null,
                "주요사항보고서(자기주식취득결정)", "에스케이하이닉스",
                LocalDate.of(2026, 4, 23), "유", "000660"
        ));

        var result = mockMvc.perform(get("/v1/disclosures/recent?limit=10&since_hours=168"))
                .andExpect(status().isOk())
                // Enriched row (cached summary): every AI field present
                // with the exact values written in seed().
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].importanceScore")
                        .value(9))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].eventType")
                        .value("RIGHTS_OFFERING"))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].sectorTags[0]")
                        .value("Information Technology"))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].tickerTags[0]")
                        .value("005930"))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].actionableFor")
                        .isArray())
                // Bare row (no cached summary): every AI field absent,
                // raw DART metadata still present.
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000002')].importanceScore")
                        .doesNotExist())
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000002')].eventType")
                        .doesNotExist())
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000002')].sectorTags")
                        .doesNotExist())
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000002')].ticker")
                        .value("000660"))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000002')].corpName")
                        .value("에스케이하이닉스"))
                .andReturn();

        // Hard guard: the paid summary text must never appear in the
        // free feed, even for filings whose summary is cached.
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("summaryEn")
                .doesNotContain("Samsung announced a rights offering decision.");
    }

    /**
     * Round-17a: the free /recent feed carries the DART source link and
     * the numericExpectation pre-purchase signal on every row, so an
     * agent can audit and rank-order before paying. The seeded
     * RIGHTS_OFFERING is a HIGH numeric-expectation event type.
     */
    @Test
    void recentFeedCarriesSourceUrlAndNumericExpectation() throws Exception {
        mockMvc.perform(get("/v1/disclosures/recent?limit=10&since_hours=168"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].sourceUrl")
                        .value(org.hamcrest.Matchers.hasItem(
                                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260423000001")))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].numericExpectation")
                        .value(org.hamcrest.Matchers.hasItem("HIGH")));
    }

    /**
     * Round-18: the FREE feed answers in English. An agent browsing
     * /recent to decide what to buy previously saw only the Korean
     * company name and the Korean DART form name — on a product whose
     * whole premise is English-readable Korean market data.
     */
    @Test
    void recentFeedCarriesEnglishCompanyNameAndFilingLabel() throws Exception {
        mockMvc.perform(get("/v1/disclosures/recent?limit=10&since_hours=168"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].corpNameEn")
                        .value(org.hamcrest.Matchers.hasItem("SAMSUNG ELECTRONICS CO,.LTD")))
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].reportNmEn")
                        .value(org.hamcrest.Matchers.hasItem("Rights Offering (Paid-in Capital Increase)")))
                // Korean canonical values stay available…
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].corpName")
                        .value(org.hamcrest.Matchers.hasItem("삼성전자")))
                // …but DART's fixed-width padding is stripped.
                .andExpect(jsonPath("$.filings[?(@.rcptNo == '20260423000001')].reportNm")
                        .value(org.hamcrest.Matchers.hasItem("주요사항보고서(유상증자결정)")));
    }

    /**
     * Round-18: the PAID response identifies the company. Before this,
     * a buyer received an English summary plus a six-digit ticker and
     * had to make a second call to learn which company the filing was
     * even about.
     */
    @Test
    void paidSummaryCarriesCompanyIdentityInEnglish() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"transaction\":\"0xE18\",\"network\":\"eip155:84532\",\"payer\":\"0x857\"}")));

        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", validPaymentPayloadBase64("sig-18-identity")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corpNameEn").value("SAMSUNG ELECTRONICS CO,.LTD"))
                .andExpect(jsonPath("$.corpName").value("삼성전자"))
                .andExpect(jsonPath("$.reportNmEn").value("Rights Offering (Paid-in Capital Increase)"))
                .andExpect(jsonPath("$.reportNm").value("주요사항보고서(유상증자결정)"));
    }

    /**
     * Round-17a: the paid /summary response carries sourceUrl (audit
     * path) and numericExpectation. Uses the seeded cached RIGHTS_OFFERING
     * row so generation isn't triggered.
     */
    @Test
    void paidSummaryCarriesSourceUrlAndNumericExpectation() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"transaction\":\"0xEE\",\"network\":\"eip155:84532\",\"payer\":\"0x857\"}")));

        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", validPaymentPayloadBase64("sig-17a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceUrl")
                        .value("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260423000001"))
                .andExpect(jsonPath("$.numericExpectation").value("HIGH"));
    }

    /**
     * Round-16 regression guard for the round-15c "paid call serves a
     * classifier stub with null summaryEn" bug. A filing that has ONLY
     * a rule-based stub row (summary_en = NULL, model rule-v1) must be
     * treated as a cache miss on the paid /summary path — triggering
     * LLM generation — NOT returned as a 200 carrying an empty summary.
     *
     * <p>Generation deterministically fails here (Gemini is pointed at
     * WireMock returning 500), so the contract is a 503 (uncharged via
     * settlement-on-2xx). The critical property is that it is NEVER a
     * 200 with a null summaryEn — which is exactly what the buggy code
     * returned, and what reached the 2026-06-12 paid caller.
     */
    @Test
    void paidSummaryOnClassifierStubDoesNotServeNullSummary() throws Exception {
        disclosureRepository.save(new Disclosure(
                "20260601000777", "00999999", "테스트배당기업", null,
                "현금ㆍ현물배당결정", "테스트배당기업",
                LocalDate.of(2026, 6, 1), "유", "099999"));
        // ONLY a classifier stub — no LLM English summary text.
        summaryRepository.save(new DisclosureSummary(
                "20260601000777", null, 5, "DIVIDEND_DECISION",
                List.of(), List.of("099999"), List.of("traders"),
                DisclosureSummary.RULE_BASED_MODEL_ID, 0, 0,
                new BigDecimal("0.00000000"), DisclosureSummary.RULE_BASED_PROMPT_VERSION));

        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"transaction\":\"0xDD\",\"network\":\"eip155:84532\",\"payer\":\"0x857\"}")));
        // Lazy generation calls Gemini → WireMock 500 → generation fails
        // → the stub is NOT upgraded to an LLM summary. (Any unstubbed
        // LLM/DART path also 404s through WireMock, so generation fails
        // deterministically regardless.)
        wireMock.stubFor(post(urlMatching(".*generateContent.*"))
                .willReturn(aResponse().withStatus(500)));

        // Pre-round-16 this returned 200 with summaryEn=null. Now the
        // stub is a cache miss → generate → generation fails in test →
        // 503, never a 200 carrying an empty summary.
        // NOTE: the signed payload's resource URL must match the request
        // URL exactly (the interceptor rejects a mismatch with 402), so
        // use the two-arg helper with THIS rcptNo — the one-arg helper
        // hardcodes rcptNo=20260423000001.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260601000777")
                        .header("PAYMENT-SIGNATURE", validPaymentPayloadBase64("sig-stub-777",
                                "http://localhost/v1/disclosures/summary?rcptNo=20260601000777")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void byTickerWithFewerFilingsThanLimitReportsChargedForVsDelivered() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"transaction\":\"0xRR\",\"network\":\"eip155:84532\",\"payer\":\"0x857\"}")));

        // Seed only one disclosure for ticker 005930 in @BeforeEach;
        // request limit=10 — agent paid for 10, only 1 delivered.
        String byTickerUrl = "http://localhost/v1/disclosures/by-ticker?ticker=005930&limit=10";
        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=10")
                        .header("PAYMENT-SIGNATURE",
                                validPaymentPayloadBase64("sig-fewer", byTickerUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("005930"))
                .andExpect(jsonPath("$.chargedFor").value(10))
                .andExpect(jsonPath("$.delivered").value(1))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.summaries.length()").value(1));
    }

    @Test
    void byTickerMalformedPaymentHeaderAlsoReturns400() throws Exception {
        // Same spec rule for per-result endpoints — a future change
        // that adds PER_RESULT-mode-specific header parsing must not
        // accidentally regress this branch on the by-ticker endpoint.
        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=3")
                        .header("PAYMENT-SIGNATURE", "not-valid-base64!@#"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_payment"))
                .andExpect(jsonPath("$.message").value(containsString("Malformed")));
    }

    @Test
    void resourceUrlMismatchInSignedPayloadReturns402() throws Exception {
        // Cross-endpoint replay defence: a payment signed for the cheap
        // fixed-price endpoint must NOT verify on the more expensive
        // per-result endpoint. The interceptor compares
        // paymentPayload.resource.url to the actual request URL and
        // refuses on mismatch.
        String summaryUrl = "http://localhost/v1/disclosures/summary?rcptNo=20260423000001";
        String header = validPaymentPayloadBase64("sig-mismatch", summaryUrl);

        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=10")
                        .header("PAYMENT-SIGNATURE", header))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value(containsString("Resource URL mismatch")));
    }

    @Test
    void settlementFailureReleasesSignatureSoClientCanRetryWithSameNonce() throws Exception {
        // After /settle fails, the EIP-3009 nonce inside the signed
        // authorisation was NOT consumed on-chain — so the same signed
        // payload is still cryptographically valid. The Redis lock
        // therefore must be released; otherwise a transient facilitator
        // outage permanently strands a paying client. This test fails
        // /settle on the first attempt, then succeeds on the second
        // (using the same payment header) and asserts the second call
        // produces a 200.
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        // First /settle call: reject. Second: succeed.
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .inScenario("retry-after-fail")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"errorReason\":\"facilitator_busy\"}"))
                .willSetStateTo("recovered"));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .inScenario("retry-after-fail")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":true,"transaction":"0xRECOVERED","network":"eip155:84532","payer":"0x857"}
                                """)));

        String header = validPaymentPayloadBase64("sig-recover");

        // First attempt: settle fails → 402 with PAYMENT-RESPONSE.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", header))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("PAYMENT-RESPONSE"));

        // Second attempt with the same header: lock was released, settle
        // succeeds → 200 with the summary.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", header))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rcptNo").value("20260423000001"));
    }

    @Test
    void facilitatorInvalidSignatureReturns402WithReason() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":false,\"invalidReason\":\"invalid_signature\"}")));

        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("X-PAYMENT", validPaymentPayloadBase64("sig-a")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value(containsString("invalid_signature")));
    }

    @Test
    void facilitatorValidAcceptanceReturns200WithSummaryAndSettles() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":true,"transaction":"0xABC123","network":"eip155:84532","payer":"0x857b06519E91e3A54538791bDbb0E22373e36b66"}
                                """)));

        long beforeCount = paymentLogRepository.count();

        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("X-PAYMENT", validPaymentPayloadBase64("sig-b")))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-PAYMENT-RESPONSE"))
                .andExpect(jsonPath("$.rcptNo").value("20260423000001"))
                .andExpect(jsonPath("$.summaryEn").value(containsString("rights offering")))
                .andExpect(jsonPath("$.importanceScore").value(9))
                .andExpect(jsonPath("$.eventType").value("RIGHTS_OFFERING"))
                .andExpect(jsonPath("$.tickerTags[0]").value("005930"));

        wireMock.verify(postRequestedFor(urlPathEqualTo("/verify")));
        wireMock.verify(postRequestedFor(urlPathEqualTo("/settle")));

        // Round-9/V11 regression guard — the round-7 silent-drop bug
        // returned a 200 + a settlement header for paid calls while
        // the payment_log row was being silently dropped by the
        // exception handler. Asserting on the controller response
        // alone (as the original version of this test did) would
        // pass even under that broken state. Lock in that the row
        // actually lands.
        long afterCount = paymentLogRepository.count();
        org.assertj.core.api.Assertions.assertThat(afterCount).isEqualTo(beforeCount + 1);

        // Cross-check that the new row carries the on-chain tx hash
        // — a row with `facilitator_tx_id IS NULL` would trip the
        // PaymentLogReconciliationMonitor SLO gauge and is never an
        // expected outcome on the success path.
        com.dartintel.api.payment.PaymentLog lastRow = paymentLogRepository.findAll().stream()
                .max(java.util.Comparator.comparing(com.dartintel.api.payment.PaymentLog::getSettledAt))
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(lastRow.getFacilitatorTxId()).isEqualTo("0xABC123");
        org.assertj.core.api.Assertions.assertThat(lastRow.getPayerAddress()).isEqualTo("0x857b06519E91e3A54538791bDbb0E22373e36b66");
        org.assertj.core.api.Assertions.assertThat(lastRow.getEndpoint()).contains("rcptNo=20260423000001");
        org.assertj.core.api.Assertions.assertThat(lastRow.getNetwork()).isEqualTo("eip155:84532");
        // signature_hash uses the round-7 "nonce:" + 0x + 64-hex
        // shape (72 chars). Pre-V11 this would have silently 22001'd
        // and dropped the row. Asserting on length is the simplest
        // proof the V11 widening took effect end-to-end through the
        // full settle path, not just at the entity layer.
        org.assertj.core.api.Assertions.assertThat(lastRow.getSignatureHash()).startsWith("nonce:0x");
        org.assertj.core.api.Assertions.assertThat(lastRow.getSignatureHash().length()).isGreaterThan(64);
    }

    @Test
    void repeatedSignatureIsRejectedAsReplay() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true,\"payer\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"transaction\":\"0xFIRST\",\"network\":\"eip155:84532\",\"payer\":\"0x857\"}")));

        String sameHeader = validPaymentPayloadBase64("sig-replay");
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001").header("X-PAYMENT", sameHeader))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001").header("X-PAYMENT", sameHeader))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value(containsString("reused")));
    }

    private String validPaymentPayloadBase64(String nonce) throws Exception {
        return validPaymentPayloadBase64(nonce,
                "http://localhost/v1/disclosures/summary?rcptNo=20260423000001");
    }

    // ----- round-12 agent UX additions -----

    @Test
    void sampleEndpointReturnsBodyAwareSummaryWithoutPayment() throws Exception {
        // /v1/disclosures/sample collapses the discovery → first-paid-call
        // gap that the round-11 24h logs flagged: agents can inspect the
        // exact response shape (with body-aware Samsung dividend numbers)
        // before signing anything. Free, never charges, never touches DART
        // or Gemini.
        mockMvc.perform(get("/v1/disclosures/sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rcptNo").value("20260430800106"))
                .andExpect(jsonPath("$.eventType").value("DIVIDEND_DECISION"))
                .andExpect(jsonPath("$.importanceScore").value(7))
                .andExpect(jsonPath("$.summaryEn").value(
                        org.hamcrest.Matchers.containsString("KRW 372 per common share")))
                .andExpect(jsonPath("$.summaryEn").value(
                        org.hamcrest.Matchers.containsString("KRW 2,453,315,636,604")))
                .andExpect(jsonPath("$.tickerTags[0]").value("005930"));
    }

    @Test
    void summaryWithoutRcptNoCarriesAgentActionHint() throws Exception {
        // Round-12 error envelope upgrade — the 400 for a missing required
        // param now carries `agent_action_hint` pointing at the free
        // discovery endpoint that would unblock the agent. Funnel
        // self-recovery without docs. The paywall interceptor's
        // pre-flight required-param check fires this 400 (before
        // Spring's @RequestParam binding), so the envelope comes from
        // X402PaywallInterceptor.writeBadRequest, which round-12
        // unified with ApiExceptionHandler's shape.
        mockMvc.perform(get("/v1/disclosures/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_parameter"))
                .andExpect(jsonPath("$.agent_action_hint").value(
                        org.hamcrest.Matchers.containsString("/v1/disclosures/recent")))
                .andExpect(jsonPath("$.agent_action_hint").value(
                        org.hamcrest.Matchers.containsString("/v1/disclosures/sample")));
    }

    @Test
    void byTickerWithNonNumericLimitReturns400BeforePaywall() throws Exception {
        // Round-12.1 — `?limit=abc` used to silently fall back to
        // defaultCount (5) and emit a 402 at 0.005×5; the agent would
        // sign an EIP-3009 authorisation against that price and only
        // then learn @Min/@Max rejects with 400 on the retry. Pre-flight
        // the count param the same way required-params already are.
        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("limit must be an integer")))
                .andExpect(jsonPath("$.agent_action_hint").value(
                        org.hamcrest.Matchers.containsString("limit={integer in [1, 50]}")));
    }

    @Test
    void byTickerWithLimitOutOfRangeReturns400BeforePaywall() throws Exception {
        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("out of range")));
    }

    @Test
    void byTickerWithLimitZeroReturns400BeforePaywall() throws Exception {
        mockMvc.perform(get("/v1/disclosures/by-ticker?ticker=005930&limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("out of range")));
    }

    @Test
    void byTickerWithoutTickerCarriesAgentActionHint() throws Exception {
        mockMvc.perform(get("/v1/disclosures/by-ticker"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_parameter"))
                .andExpect(jsonPath("$.agent_action_hint").value(
                        org.hamcrest.Matchers.containsString("/v1/companies?q=")));
    }

    @Test
    void malformedPaymentHeaderCarriesAgentActionHint() throws Exception {
        // Existing test asserts 400 + "Malformed" in $.error — round-12
        // upgraded the envelope to error code + message + hint, so
        // assert the hint points at the wire-shape doc + sample endpoint.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("PAYMENT-SIGNATURE", "not-valid-base64!@#"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_payment"))
                .andExpect(jsonPath("$.agent_action_hint").value(
                        org.hamcrest.Matchers.containsString("PaymentPayload")))
                .andExpect(jsonPath("$.agent_action_hint").value(
                        org.hamcrest.Matchers.containsString("/v1/disclosures/sample")));
    }

    @Test
    void paidEndpoint402CarriesRateLimitAdvisoryHeaders() throws Exception {
        // Round-12 — paid endpoint responses (including the 402 challenge)
        // advertise the upstream-bound rate limit so agents can schedule
        // batch traffic without hitting Gemini RPM ceiling.
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001"))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().string("X-RateLimit-Limit", "10"))
                .andExpect(header().string("X-RateLimit-Window-Seconds", "60"));
    }

    private String validPaymentPayloadBase64(String nonce, String resourceUrl) throws Exception {
        PaymentPayload payload = new PaymentPayload(
                2,
                new ResourceInfo(
                        resourceUrl,
                        "DART summary",
                        "application/json"
                ),
                new PaymentRequirement(
                        "exact", "eip155:84532", "5000",
                        "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                        "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                        300,
                        Map.of("name", "USDC", "version", "2")
                ),
                new EvmExactPayload(
                        "0x" + "a".repeat(130),
                        new EvmExactPayload.Eip3009Authorization(
                                "0x857b06519E91e3A54538791bDbb0E22373e36b66",
                                "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                                "5000",
                                "1740672089",
                                "1740672154",
                                "0x" + nonce + "0".repeat(62 - nonce.length())
                        )
                )
        );
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(payload));
    }
}
