package com.dartintel.api.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PublicControllerIT {

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

    @Test
    void pricingReturnsAllPaidEndpointsAndX402Config() throws Exception {
        // Paths are sorted alphabetically. After v1.1 added by-ticker, the
        // payable list is two entries deep: by-ticker (cheaper letter, comes
        // first) then the original /{rcptNo}/summary route. Assert by path
        // pattern rather than index to keep the test stable as future paid
        // endpoints land.
        mockMvc.perform(get("/v1/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.x402Network").value("eip155:84532"))
                .andExpect(jsonPath("$.x402Asset").value("0x036CbD53842c5426634e7929541eC2318f3dCF7e"))
                .andExpect(jsonPath("$.x402Recipient").value("0x209693Bc6afc0C5328bA36FaF03C514EF312287C"))
                .andExpect(jsonPath("$.endpoints").isArray())
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/{rcptNo}/summary')].priceUsdc")
                        .value("0.005"))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/by-ticker/{ticker}')].priceUsdc")
                        .value("0.005"));
    }

    @Test
    void pricingIsFreeAndDoesNotRequireXPaymentHeader() throws Exception {
        // Deliberately no X-PAYMENT header — must still return 200.
        mockMvc.perform(get("/v1/pricing"))
                .andExpect(status().isOk());
    }
}
