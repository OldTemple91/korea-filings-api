package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settles verified x402 payments and attaches the {@code PAYMENT-RESPONSE}
 * header (plus the legacy {@code X-PAYMENT-RESPONSE} alias) to the
 * outbound response before the body is written. Runs only for
 * controller methods annotated with {@link X402Paywall}; other
 * responses pass through untouched.
 *
 * <p>Running at {@link ResponseBodyAdvice#beforeBodyWrite} (rather
 * than
 * {@link org.springframework.web.servlet.HandlerInterceptor#afterCompletion})
 * is what makes the settlement header land on the wire — once Spring
 * has started streaming the @ResponseBody, headers are frozen.
 *
 * <p>PaymentLog persistence lives here too: it belongs with the
 * settlement transaction, and the request's {@link VerifiedPayment}
 * attribute gives every field needed.
 *
 * <h3>Fail-closed on settlement failure</h3>
 *
 * <p>If the facilitator's {@code /settle} call throws or returns a
 * non-success result, the controller's response body is replaced with
 * a 502 error envelope and the original payload is dropped. Returning
 * the bought data while the merchant is unpaid would be a
 * non-revocable revenue leak — verify is binding, but settle is what
 * actually transfers funds, and a server that delivers data on a
 * failed settle is effectively giving it away whenever the
 * facilitator has an outage.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class X402SettlementAdvice implements ResponseBodyAdvice<Object> {

    /** v2 transport — preferred server-to-client settlement header. */
    static final String PAYMENT_RESPONSE_HEADER = "PAYMENT-RESPONSE";
    /** v1 compat — emitted alongside the v2 header until 0.3.x SDK adoption is observable. */
    static final String LEGACY_X_PAYMENT_RESPONSE_HEADER = "X-PAYMENT-RESPONSE";

    private final FacilitatorClient facilitatorClient;
    private final PaymentLogRepository paymentLogRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(X402Paywall.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletReq)
                || !(response instanceof ServletServerHttpResponse servletResp)) {
            return body;
        }

        int status = servletResp.getServletResponse().getStatus();
        if (status < 200 || status >= 300) {
            return body;  // Non-2xx — interceptor's afterCompletion releases the signature.
        }

        HttpServletRequest httpReq = servletReq.getServletRequest();
        Object attr = httpReq.getAttribute(X402PaywallInterceptor.REQUEST_ATTR_VERIFIED);
        if (!(attr instanceof VerifiedPayment verified)) {
            return body;
        }

        FacilitatorSettleResponse settle;
        try {
            settle = facilitatorClient.settle(new FacilitatorSettleRequest(
                    X402PaywallInterceptor.X402_VERSION,
                    verified.payload(),
                    verified.requirement()));
        } catch (Exception e) {
            log.error("x402 settle exception for endpoint={}: {}",
                    verified.endpoint(), e.getMessage(), e);
            return failClosed(servletResp, "settle_unavailable",
                    "x402 facilitator unreachable; payment not captured");
        }

        if (!settle.success()) {
            String reason = settle.errorReason() != null ? settle.errorReason() : "unknown";
            log.error("x402 settle rejected: reason={} endpoint={}",
                    reason, verified.endpoint());
            return failClosed(servletResp, "settle_rejected",
                    "x402 facilitator rejected the payment: " + reason);
        }

        attachSettlementHeader(response, settle);
        persistPaymentLog(verified, settle);
        log.info("x402 settled: endpoint={} payer={} amount={} tx={}",
                verified.endpoint(), verified.payer(),
                verified.requirement().amount(), settle.transaction());

        return body;
    }

    /**
     * Replace the controller's body with a 502 error envelope so a
     * failed settlement does not deliver paid data unpaid. The
     * payment signature stays locked in Redis for its 1-hour TTL —
     * the client cannot retry the same signature, but they can sign
     * a fresh one once the facilitator is healthy.
     */
    private Object failClosed(ServletServerHttpResponse servletResp, String code, String message) {
        servletResp.getServletResponse().setStatus(HttpStatus.BAD_GATEWAY.value());
        servletResp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", code);
        envelope.put("message", message);
        return envelope;
    }

    private void attachSettlementHeader(ServerHttpResponse response, FacilitatorSettleResponse settle) {
        try {
            byte[] proofBytes = objectMapper.writeValueAsBytes(settle);
            String proof = Base64.getEncoder().encodeToString(proofBytes);
            response.getHeaders().add(PAYMENT_RESPONSE_HEADER, proof);
            response.getHeaders().add(LEGACY_X_PAYMENT_RESPONSE_HEADER, proof);
        } catch (Exception e) {
            log.error("Failed to encode PAYMENT-RESPONSE header: {}", e.getMessage());
        }
    }

    private void persistPaymentLog(VerifiedPayment verified, FacilitatorSettleResponse settle) {
        String rcptNo = X402PaywallInterceptor.extractRcptNo(verified.endpoint());
        BigDecimal amountHuman = new BigDecimal(verified.requirement().amount())
                .divide(X402PaywallInterceptor.USDC_ATOMIC, 6, RoundingMode.HALF_UP);
        paymentLogRepository.save(new PaymentLog(
                rcptNo,
                verified.endpoint(),
                amountHuman,
                verified.payer(),
                verified.requirement().network(),
                settle.transaction(),
                verified.signatureHash()
        ));
    }
}
