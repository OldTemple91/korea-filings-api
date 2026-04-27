package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.EvmExactPayload;
import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import com.dartintel.api.payment.dto.PaymentPayload;
import com.dartintel.api.payment.dto.PaymentRequirement;
import com.dartintel.api.payment.dto.ResourceInfo;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class FacilitatorClientTest {

    private static WireMockServer wireMock;
    private FacilitatorClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        X402Properties props = new X402Properties(
                "http://localhost:" + wireMock.port(),
                "eip155:84532",
                "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                300,
                new X402Properties.Timeout(2000, 5000),
                new X402Properties.Replay(3600),
                new X402Properties.Cdp("", "")
        );
        client = new FacilitatorClient(WebClient.builder(), props, new CdpJwtSigner(props));
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void verifyParsesSuccessfulIsValidResponse() {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "isValid": true,
                                  "payer": "0x857b06519E91e3A54538791bDbb0E22373e36b66",
                                  "scheme": "exact",
                                  "network": "eip155:84532"
                                }
                                """)));

        FacilitatorVerifyResponse resp = client.verify(sampleVerifyRequest());

        assertThat(resp.isValid()).isTrue();
        assertThat(resp.invalidReason()).isNull();
        assertThat(resp.payer()).isEqualTo("0x857b06519E91e3A54538791bDbb0E22373e36b66");
        assertThat(resp.network()).isEqualTo("eip155:84532");
    }

    @Test
    void verifyParsesInvalidResponse() {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\": false, \"invalidReason\": \"invalid_signature\"}")));

        FacilitatorVerifyResponse resp = client.verify(sampleVerifyRequest());

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.invalidReason()).isEqualTo("invalid_signature");
    }

    @Test
    void verifyBodyCarriesPaymentPayloadAndRequirements() {
        wireMock.stubFor(post(urlPathEqualTo("/verify"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isValid\":true}")));

        client.verify(sampleVerifyRequest());

        wireMock.verify(postRequestedFor(urlPathEqualTo("/verify"))
                .withRequestBody(containing("\"x402Version\":2"))
                .withRequestBody(containing("\"paymentPayload\""))
                .withRequestBody(containing("\"paymentRequirements\""))
                .withRequestBody(containing("\"scheme\":\"exact\""))
                .withRequestBody(containing("\"network\":\"eip155:84532\""))
        );
    }

    @Test
    void settleParsesSuccessWithTransactionHash() {
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "success": true,
                                  "transaction": "0xabc123",
                                  "network": "eip155:84532",
                                  "payer": "0x857b06519E91e3A54538791bDbb0E22373e36b66"
                                }
                                """)));

        FacilitatorSettleResponse resp = client.settle(sampleSettleRequest());

        assertThat(resp.success()).isTrue();
        assertThat(resp.transaction()).isEqualTo("0xabc123");
        assertThat(resp.errorReason()).isNull();
    }

    @Test
    void settleParsesFailureWithErrorReason() {
        wireMock.stubFor(post(urlPathEqualTo("/settle"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": false, \"errorReason\": \"insufficient_balance\"}")));

        FacilitatorSettleResponse resp = client.settle(sampleSettleRequest());

        assertThat(resp.success()).isFalse();
        assertThat(resp.errorReason()).isEqualTo("insufficient_balance");
        assertThat(resp.transaction()).isNull();
    }

    private static FacilitatorVerifyRequest sampleVerifyRequest() {
        return new FacilitatorVerifyRequest(2, samplePaymentPayload(), sampleRequirement());
    }

    private static FacilitatorSettleRequest sampleSettleRequest() {
        return new FacilitatorSettleRequest(2, samplePaymentPayload(), sampleRequirement());
    }

    private static PaymentPayload samplePaymentPayload() {
        return new PaymentPayload(
                2,
                new ResourceInfo("http://localhost/v1/disclosures/x/summary", "DART summary", "application/json"),
                sampleRequirement(),
                new EvmExactPayload("0xsig", new EvmExactPayload.Eip3009Authorization(
                        "0xfrom", "0xto", "5000", "1740672089", "1740672154", "0xnonce"))
        );
    }

    private static PaymentRequirement sampleRequirement() {
        return new PaymentRequirement(
                "exact", "eip155:84532", "5000",
                "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                300,
                Map.of("name", "USDC", "version", "2")
        );
    }
}
