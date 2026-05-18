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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        // Paths are sorted alphabetically. The payable list is two entries
        // deep: /v1/disclosures/by-ticker (cheaper letter, comes first) and
        // /v1/disclosures/summary. Assert by path pattern rather than index
        // to keep the test stable as future paid endpoints land.
        mockMvc.perform(get("/v1/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.x402Network").value("eip155:84532"))
                .andExpect(jsonPath("$.x402Asset").value("0x036CbD53842c5426634e7929541eC2318f3dCF7e"))
                .andExpect(jsonPath("$.x402Recipient").value("0x209693Bc6afc0C5328bA36FaF03C514EF312287C"))
                .andExpect(jsonPath("$.endpoints").isArray())
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/summary')].priceUsdc")
                        .value("0.005"))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/by-ticker')].priceUsdc")
                        .value("0.005"))
                // Header convention block must advertise PAYMENT-SIGNATURE as preferred.
                .andExpect(jsonPath("$.paymentHeaders.preferred").value("PAYMENT-SIGNATURE"))
                .andExpect(jsonPath("$.paymentHeaders.accepted[*]").value(
                        org.hamcrest.Matchers.hasItem("X-PAYMENT")))
                .andExpect(jsonPath("$.paymentHeaders.settlement").value("PAYMENT-RESPONSE"))
                // Workflow block must list at least three steps (free → paid → optional re-fetch).
                .andExpect(jsonPath("$.workflow.steps.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                // Required params: by-ticker must declare ticker as required.
                .andExpect(jsonPath(
                        "$.endpoints[?(@.path == '/v1/disclosures/by-ticker')].requiredParams[?(@.name == 'ticker')].required")
                        .value(true));
    }

    @Test
    void pricingIsFreeAndDoesNotRequireAnyPaymentHeader() throws Exception {
        // Deliberately no payment header — must still return 200.
        mockMvc.perform(get("/v1/pricing"))
                .andExpect(status().isOk());
    }

    @Test
    void pricingByTickerSampleHasBatchEnvelopeShape() throws Exception {
        // Round-12.1 — the by-ticker endpoint actually returns
        // ByTickerResponse (ticker / count / chargedFor / delivered /
        // summaries[]), not a bare DisclosureSummaryDto. Round-12
        // originally attached the per-row shape to both paid endpoints;
        // an agent auto-generating a parser from
        // /v1/pricing.endpoints[].sampleResponse for the batch endpoint
        // would then mis-parse the actual response. Sample shape must
        // match the wire shape per endpoint.
        mockMvc.perform(get("/v1/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/by-ticker')].sampleResponse.ticker")
                        .value(org.hamcrest.Matchers.hasItem("005930")))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/by-ticker')].sampleResponse.chargedFor")
                        .value(org.hamcrest.Matchers.hasItem(3)))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/by-ticker')].sampleResponse.delivered")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/by-ticker')].sampleResponse.summaries[0].rcptNo")
                        .value(org.hamcrest.Matchers.hasItem("20260430800106")));
    }

    @Test
    void pricingCarriesSampleResponseOnEachPaidEndpoint() throws Exception {
        // Round-12 surface lift — agents discovering /v1/pricing should
        // see "what does 0.005 USDC actually buy?" before signing
        // anything. The sample is the body-aware Samsung Q1 dividend
        // response from the round-11 ship-day mainnet test.
        // jsonPath filters return JSONArray even on single-match, so
        // wrap each value matcher with hasItem(...) to assert the
        // single element inside.
        mockMvc.perform(get("/v1/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/summary')].sampleResponse.rcptNo")
                        .value(org.hamcrest.Matchers.hasItem("20260430800106")))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/summary')].sampleResponse.eventType")
                        .value(org.hamcrest.Matchers.hasItem("DIVIDEND_DECISION")))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/summary')].sampleResponse.summaryEn")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("KRW 372 per common share"))))
                .andExpect(jsonPath("$.endpoints[?(@.path == '/v1/disclosures/summary')].sampleSettlementTxUrl")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("basescan.org/tx/0x5a0403ae"))));
    }

    /**
     * Production traffic shows agents POSTing to our paid GET endpoints
     * (most x402 examples on the public web are LLM-inference services
     * that POST). Spring's default 405 envelope is empty, which gives
     * the agent no recovery hint. Verify our advice replaces it with a
     * structured body that names the supported verb, points at the
     * discovery doc, and ships the {@code Allow} header per HTTP/1.1.
     */
    @Test
    void postToGetOnlyEndpointReturns405WithMachineReadableHint() throws Exception {
        mockMvc.perform(post("/v1/disclosures/summary").contentType("application/json"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.supported[0]").value("GET"))
                // Round-10 fix: hint and discovery now emit ABSOLUTE
                // URLs so a tool-chain agent that forwards the
                // envelope to a follow-up step doesn't lose the
                // origin host. The protocol part may vary in
                // MockMvc (no real TLS termination), so assert on
                // the path tail and on the scheme://host shape.
                .andExpect(jsonPath("$.hint").value(containsString("/v1/disclosures/summary")))
                .andExpect(jsonPath("$.hint").value(containsString("://")))
                .andExpect(jsonPath("$.discovery").value(containsString("/.well-known/x402")))
                .andExpect(jsonPath("$.discovery").value(containsString("://")));
    }

    @Test
    void postToFreeGetEndpointAlsoReceivesEnrichedHint() throws Exception {
        // The advice is path-agnostic — same envelope on a free path.
        mockMvc.perform(post("/v1/pricing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andExpect(jsonPath("$.hint").value(containsString("/v1/pricing")))
                .andExpect(jsonPath("$.hint").value(containsString("://")));
    }

    /**
     * AWP (Agent Web Protocol) manifest probed by the Open402
     * directory crawler and other AWP-aware indexers. Same surface
     * as /.well-known/x402 but in the awp_version / domain / intent /
     * actions[] shape. Verify required top-level fields, that paid
     * actions carry a payment block, and that free actions don't.
     */
    @Test
    void wellKnownAgentJsonReturnsAwpManifestWithPaidAndFreeActions() throws Exception {
        mockMvc.perform(get("/.well-known/agent.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awp_version").value("0.2"))
                .andExpect(jsonPath("$.domain").value("api.koreafilings.com"))
                .andExpect(jsonPath("$.intent").value(containsString("Korean DART")))
                .andExpect(jsonPath("$.payment.scheme").value("x402"))
                .andExpect(jsonPath("$.payment.discovery").value("/.well-known/x402"))
                // free actions present — find-company is the cold-start anchor
                .andExpect(jsonPath("$.actions[?(@.id == 'find-company')].method").value("GET"))
                .andExpect(jsonPath("$.actions[?(@.id == 'find-company')].endpoint").value("/v1/companies"))
                // paid actions carry a payment block; free ones don't
                .andExpect(jsonPath("$.actions[?(@.id == 'get-by-ticker')].payment.scheme").value("x402"))
                .andExpect(jsonPath("$.actions[?(@.id == 'get-by-ticker')].payment.priceUsdc").value("0.005"))
                .andExpect(jsonPath("$.actions[?(@.id == 'get-summary')].payment.priceMode").value("fixed"))
                // free action has no payment field
                .andExpect(jsonPath("$.actions[?(@.id == 'find-company')].payment").doesNotExist());
    }

    /**
     * Canonical x402 discovery document. Verifies the spec-required
     * top-level shape (numeric {@code version}, {@code resources}
     * URL array, root-level {@code name} + {@code url}, the
     * {@code llms_txt} agent-friendliness pointer added in round-13)
     * and the nested {@code service} + {@code x402} extensions that
     * vetted catalog entries expose.
     */
    @Test
    void wellKnownX402ReturnsDiscoveryDocument() throws Exception {
        mockMvc.perform(get("/.well-known/x402"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Korea Filings"))
                .andExpect(jsonPath("$.url").value("https://api.koreafilings.com"))
                .andExpect(jsonPath("$.resources").isArray())
                .andExpect(jsonPath("$.llms_txt").value("https://api.koreafilings.com/llms.txt"))
                .andExpect(jsonPath("$.service.name").value("Korea Filings"))
                .andExpect(jsonPath("$.service.llms_txt").value("https://api.koreafilings.com/llms.txt"))
                .andExpect(jsonPath("$.x402.scheme").value("exact"));
    }

    /**
     * Some x402 indexers in the wild (observed: X402-Scanner/1.0 in
     * the 2026-05-17 production logs) probe {@code /.well-known/x402.json}
     * — with the {@code .json} suffix — and 404 on the canonical
     * extension-less path. Round-14 added the {@code .json} alias so
     * those crawlers reach the same document instead of falling through.
     */
    @Test
    void wellKnownX402JsonAliasReturnsSameDocument() throws Exception {
        mockMvc.perform(get("/.well-known/x402.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Korea Filings"))
                .andExpect(jsonPath("$.url").value("https://api.koreafilings.com"))
                .andExpect(jsonPath("$.llms_txt").value("https://api.koreafilings.com/llms.txt"));
    }

    /**
     * Casual visits to the API host (humans pasting api.koreafilings.com
     * into a browser, search engine bots) used to 404 — now 302 to
     * the marketing landing page. Indirectly proves the
     * DiscoveryRootController is wired and that the X402PaywallInterceptor
     * doesn't accidentally challenge the root path.
     */
    @Test
    void rootRedirectsToLandingPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://koreafilings.com"));
    }

    @Test
    void faviconReturns204NoContentNot404() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNoContent());
    }

    /**
     * Bingbot indexed five swagger-ui asset URLs at the bare
     * {@code /swagger-ui/<file>} path on 2026-05-17 and 404'd on
     * every one — Springdoc actually serves them one directory deeper
     * at {@code /swagger-ui/swagger-ui/<file>}. Round-14 added a 301
     * redirect for {@code .js}/{@code .css}/{@code .png} files so the
     * cached crawler URL moves to the canonical one on the next pass.
     */
    @Test
    void swaggerUiBareAssetPathsRedirectToActualPath() throws Exception {
        String[] bingObservedAssets = {
                "swagger-initializer.js",
                "swagger-ui.css",
                "index.css",
                "swagger-ui-standalone-preset.js",
                "swagger-ui-bundle.js"
        };
        for (String file : bingObservedAssets) {
            mockMvc.perform(get("/swagger-ui/" + file))
                    .andExpect(status().isMovedPermanently())
                    .andExpect(header().string("Location", "/swagger-ui/swagger-ui/" + file));
        }
    }

    /**
     * The {@code .html} extension must NOT be caught by the redirect —
     * Springdoc already publishes a redirect at
     * {@code /swagger-ui/index.html} that takes browsers to the working
     * UI. If our redirect shadowed the springdoc one the Swagger UI
     * page itself would stop loading.
     */
    @Test
    void swaggerUiIndexHtmlIsNotShadowedByAssetRedirect() throws Exception {
        // We do not assert the exact redirect target (Springdoc owns it)
        // — only that we return a redirect, not the 301 emitted by our
        // own controller, and not a 404.
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().is3xxRedirection());
    }
}
