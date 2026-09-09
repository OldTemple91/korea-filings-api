package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The facilitator only catalogs a resource when the settle/verify
 * payload carries {@code extensions.bazaar}. Clients are supposed to
 * echo it from the 402, but none of the observed ones did — so the
 * interceptor fills it in from the server's own declaration when the
 * client left it out, and leaves a client-supplied echo untouched.
 */
class X402PaywallInterceptorBazaarEchoTest {

    private static final String RESOURCE_URL = "http://localhost/v1/disclosures/summary?rcptNo=20260424900874";

    private final PaymentStore paymentStore = mock(PaymentStore.class);
    private final FacilitatorClient facilitatorClient = mock(FacilitatorClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final X402PaywallInterceptor interceptor = new X402PaywallInterceptor(
            paymentStore, facilitatorClient, properties(), objectMapper);

    @Test
    @SuppressWarnings("unchecked")
    void verifyRequestCarriesServerBazaarWhenClientOmittedIt() throws Exception {
        when(paymentStore.registerIfAbsent(anyString())).thenReturn(true);
        when(facilitatorClient.verify(any())).thenReturn(
                new FacilitatorVerifyResponse(true, null, "0xpayer", "exact", "eip155:8453"));
        MockHttpServletRequest request = paidRequest(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, handler());

        assertThat(proceed).isTrue();
        ArgumentCaptor<FacilitatorVerifyRequest> captor = ArgumentCaptor.forClass(FacilitatorVerifyRequest.class);
        verify(facilitatorClient).verify(captor.capture());
        Map<String, Object> extensions = captor.getValue().paymentPayload().extensions();
        assertThat(extensions).containsKey("bazaar");
        Map<String, Object> bazaar = (Map<String, Object>) extensions.get("bazaar");
        assertThat(bazaar).containsKeys("info", "schema");

        // The enriched payload is what settle will forward too.
        VerifiedPayment verified = (VerifiedPayment) request.getAttribute(X402PaywallInterceptor.REQUEST_ATTR_VERIFIED);
        assertThat(verified.payload().extensions()).containsKey("bazaar");

        // Provider branding is filled into the payload's resource object
        // as well, since clients build that object themselves.
        assertThat(captor.getValue().paymentPayload().resource().serviceName()).isEqualTo("Korea Filings");
        assertThat(captor.getValue().paymentPayload().resource().tags()).contains("korea");
        assertThat(captor.getValue().paymentPayload().resource().url()).isEqualTo(RESOURCE_URL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyRequestKeepsClientEchoedBazaar() throws Exception {
        when(paymentStore.registerIfAbsent(anyString())).thenReturn(true);
        when(facilitatorClient.verify(any())).thenReturn(
                new FacilitatorVerifyResponse(true, null, "0xpayer", "exact", "eip155:8453"));
        Map<String, Object> clientBazaar = Map.of("info", Map.of("client", "echo"), "schema", Map.of());
        MockHttpServletRequest request = paidRequest(Map.of("bazaar", clientBazaar));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, handler());

        ArgumentCaptor<FacilitatorVerifyRequest> captor = ArgumentCaptor.forClass(FacilitatorVerifyRequest.class);
        verify(facilitatorClient).verify(captor.capture());
        Map<String, Object> bazaar = (Map<String, Object>) captor.getValue().paymentPayload().extensions().get("bazaar");
        assertThat(bazaar).isEqualTo(clientBazaar);
    }

    private MockHttpServletRequest paidRequest(Map<String, Object> extensions) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/summary");
        request.setQueryString("rcptNo=20260424900874");
        request.addParameter("rcptNo", "20260424900874");
        request.addHeader("PAYMENT-SIGNATURE", paymentHeader(extensions));
        return request;
    }

    private String paymentHeader(Map<String, Object> extensions) throws Exception {
        Map<String, Object> accepted = Map.of(
                "scheme", "exact", "network", "eip155:8453", "amount", "5000",
                "asset", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                "payTo", "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                "maxTimeoutSeconds", 60, "extra", Map.of("name", "USD Coin", "version", "2"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("x402Version", 2);
        payload.put("resource", Map.of("url", RESOURCE_URL, "description", "DART summary", "mimeType", "application/json"));
        payload.put("accepted", accepted);
        payload.put("payload", Map.of("signature", "0xsig", "authorization", Map.of(
                "from", "0xfrom", "to", "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720", "value", "5000",
                "validAfter", "1", "validBefore", "2", "nonce", "0x" + "ab".repeat(32))));
        if (extensions != null) {
            payload.put("extensions", extensions);
        }
        byte[] json = objectMapper.writeValueAsBytes(payload);
        return Base64.getEncoder().encodeToString(json);
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new StubController(), StubController.class.getMethod("summary"));
    }

    private static X402Properties properties() {
        return new X402Properties(
                "http://facilitator.invalid", "eip155:8453",
                "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                "USD Coin", "2", 60,
                new X402Properties.Timeout(1000, 1000),
                new X402Properties.Replay(3600),
                new X402Properties.Cdp(null, null));
    }

    static class StubController {
        @X402Paywall(
                priceUsdc = "0.005",
                requiredQueryParams = {"rcptNo"},
                description = "AI-generated English summary of a Korean DART disclosure")
        public void summary() {
        }
    }
}
