package com.dartintel.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pre-paywall required-param handling on paid endpoints.
 *
 * <p>Coinbase's Bazaar indexes a paid endpoint by its canonical,
 * query-less URL and health-probes that same bare path expecting a
 * {@code 402} carrying the {@code bazaar} extension (every one of the
 * ~14k indexed resources answers its bare path with 402; the CDP
 * {@code /x402/validate} tool lists {@code returns_402} as a required
 * check). Round-12 made a missing required query param short-circuit
 * to 400 before the 402 so an agent would not sign against a
 * default-priced challenge — which silently failed that probe and
 * kept the service out of the catalog.
 *
 * <p>The reconciled rule: a bare request (required param absent, no
 * payment header) is a discovery probe and gets the 402; a request
 * that has already committed a signature, or that sends the param
 * present-but-blank, still gets the round-12 400 before any verify.
 */
class X402PaywallInterceptorRequiredParamTest {

    private final PaymentStore paymentStore = mock(PaymentStore.class);
    private final FacilitatorClient facilitatorClient = mock(FacilitatorClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final X402PaywallInterceptor interceptor = new X402PaywallInterceptor(
            paymentStore, facilitatorClient, properties(), objectMapper);

    @Test
    void bareSummaryPathWithoutPaymentReturns402Discovery() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, handler("summary"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(402);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.at("/resource/url").asText()).isEqualTo("http://localhost/v1/disclosures/summary");
        assertThat(body.at("/extensions/bazaar/info/input/queryParams/rcptNo/required").asBoolean()).isTrue();
        assertThat(response.getHeader("PAYMENT-REQUIRED")).isNotBlank();
        verify(paymentStore, never()).registerIfAbsent(anyString());
    }

    @Test
    void discovery402CarriesProviderBrandingOnResource() throws Exception {
        // The Bazaar reads serviceName / tags / iconUrl from the 402's
        // resource object to name and rank the catalog entry.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, handler("summary"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.at("/resource/serviceName").asText()).isEqualTo("Korea Filings");
        assertThat(body.at("/resource/tags").isArray()).isTrue();
        assertThat(body.at("/resource/tags").toString()).contains("\"korea\"");
        assertThat(body.at("/resource/iconUrl").asText()).startsWith("https://");
    }

    @Test
    void bareByTickerPathWithoutPaymentReturns402AtDefaultCountPrice() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/by-ticker");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, handler("byTicker"));

        assertThat(response.getStatus()).isEqualTo(402);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.at("/resource/url").asText()).isEqualTo("http://localhost/v1/disclosures/by-ticker");
        // defaultCount 5 × 0.005 USDC = 0.025 USDC = 25000 atomic
        assertThat(body.at("/accepts/0/amount").asText()).isEqualTo("25000");
        assertThat(body.at("/extensions/bazaar/info/input/queryParams/ticker/required").asBoolean()).isTrue();
    }

    @Test
    void bareSummaryPathWithPaymentHeaderReturns400BeforeVerify() throws Exception {
        // The agent already committed a signature against a challenge
        // that lacks the required param — fail fast with the round-12
        // envelope, and never touch the replay store or facilitator.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/summary");
        request.addHeader("PAYMENT-SIGNATURE", "eyJmYWtlIjp0cnVlfQ==");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, handler("summary"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("missing_parameter");
        assertThat(body.get("agent_action_hint").asText()).contains("/v1/disclosures/recent");
        verify(paymentStore, never()).registerIfAbsent(anyString());
        verify(facilitatorClient, never()).verify(any());
    }

    @Test
    void byTickerMissingTickerWithLegacyXPaymentHeaderReturns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/by-ticker");
        request.setQueryString("limit=3");
        request.addParameter("limit", "3");
        request.addHeader("X-PAYMENT", "eyJmYWtlIjp0cnVlfQ==");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, handler("byTicker"));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("missing_parameter");
        assertThat(body.get("agent_action_hint").asText()).contains("/v1/companies?q=");
    }

    @Test
    void blankRcptNoWithoutPaymentReturns400() throws Exception {
        // Present-but-blank is a malformed call, not a discovery probe.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/summary");
        request.setQueryString("rcptNo=");
        request.addParameter("rcptNo", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, handler("summary"));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("missing_parameter");
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new StubController(), StubController.class.getMethod(methodName));
    }

    private static X402Properties properties() {
        return new X402Properties(
                "http://facilitator.invalid",
                "eip155:8453",
                "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                "USD Coin",
                "2",
                60,
                new X402Properties.Timeout(1000, 1000),
                new X402Properties.Replay(3600),
                new X402Properties.Cdp(null, null));
    }

    /** Mirrors the two production paywall declarations in DisclosuresController. */
    static class StubController {
        @X402Paywall(
                priceUsdc = "0.005",
                requiredQueryParams = {"rcptNo"},
                description = "AI-generated English summary of a Korean DART disclosure")
        public void summary() {
        }

        @X402Paywall(
                priceUsdc = "0.005",
                pricingMode = X402Paywall.Mode.PER_RESULT,
                countQueryParam = "limit",
                defaultCount = 5,
                maxCount = 50,
                requiredQueryParams = {"ticker"},
                description = "AI summaries for the most recent DART filings of a Korean ticker")
        public void byTicker() {
        }
    }
}
