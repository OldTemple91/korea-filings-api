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
        disclosureRepository.save(new Disclosure(
                "20260423000001", "00126380", "삼성전자", null,
                "주요사항보고서(유상증자결정)", "삼성전자",
                LocalDate.of(2026, 4, 23), "유", "005930"
        ));
        summaryRepository.save(new DisclosureSummary(
                "20260423000001",
                "Samsung announced a rights offering decision.",
                9, "RIGHTS_OFFERING",
                List.of("Information Technology"), List.of("005930"),
                List.of("traders", "long_term_investors"),
                "gemini-2.5-flash-lite", 142, 89,
                new BigDecimal("0.00004980")
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
        mockMvc.perform(get("/v1/disclosures/summary?rcptNo=20260423000001")
                        .header("X-PAYMENT", "not-valid-base64!@#"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Malformed")));
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
                .andExpect(jsonPath("$.error").value(containsString("ticker")));
    }

    @Test
    void summaryWithoutRcptNoReturns400BeforePaywall() throws Exception {
        mockMvc.perform(get("/v1/disclosures/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("rcptNo")));
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
                .andExpect(jsonPath("$.error").value(containsString("Malformed")));
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
