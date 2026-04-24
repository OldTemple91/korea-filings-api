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
                LocalDate.of(2026, 4, 23), "유"
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
    void missingXPaymentHeaderReturns402WithPaymentRequirements() throws Exception {
        mockMvc.perform(get("/v1/disclosures/20260423000001/summary"))
                .andExpect(status().isPaymentRequired())
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
    void malformedXPaymentHeaderReturns402() throws Exception {
        mockMvc.perform(get("/v1/disclosures/20260423000001/summary")
                        .header("X-PAYMENT", "not-valid-base64!@#"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value(containsString("Malformed")));
    }

    @Test
    void facilitatorInvalidSignatureReturns402WithReason() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":false,\"invalidReason\":\"invalid_signature\"}")));

        mockMvc.perform(get("/v1/disclosures/20260423000001/summary")
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

        mockMvc.perform(get("/v1/disclosures/20260423000001/summary")
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
        mockMvc.perform(get("/v1/disclosures/20260423000001/summary").header("X-PAYMENT", sameHeader))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/disclosures/20260423000001/summary").header("X-PAYMENT", sameHeader))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value(containsString("reused")));
    }

    private String validPaymentPayloadBase64(String nonce) throws Exception {
        PaymentPayload payload = new PaymentPayload(
                2,
                new ResourceInfo(
                        "http://localhost/v1/disclosures/20260423000001/summary",
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
