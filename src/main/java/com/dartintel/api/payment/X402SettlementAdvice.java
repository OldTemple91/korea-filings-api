package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
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

/**
 * Settles verified x402 payments and attaches the X-PAYMENT-RESPONSE
 * header to the outbound response before the body is written. Runs
 * only for controller methods annotated with {@link X402Paywall};
 * other responses pass through untouched.
 *
 * Running at {@link ResponseBodyAdvice#beforeBodyWrite} (rather than
 * {@link org.springframework.web.servlet.HandlerInterceptor#afterCompletion})
 * is what makes the settlement header land on the wire — once Spring
 * has started streaming the @ResponseBody, headers are frozen.
 *
 * PaymentLog persistence lives here too: it belongs with the
 * settlement transaction, and the request's VerifiedPayment attribute
 * gives us every field we need.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class X402SettlementAdvice implements ResponseBodyAdvice<Object> {

    static final String X_PAYMENT_RESPONSE_HEADER = "X-PAYMENT-RESPONSE";

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
            log.error("x402 settle exception for endpoint={}: {}", verified.endpoint(), e.getMessage(), e);
            return body;
        }

        if (!settle.success()) {
            log.error("x402 settle rejected: reason={} endpoint={}",
                    settle.errorReason(), verified.endpoint());
            return body;
        }

        attachSettlementHeader(response, settle);
        persistPaymentLog(verified, settle);
        log.info("x402 settled: endpoint={} payer={} amount={} tx={}",
                verified.endpoint(), verified.payer(),
                verified.requirement().amount(), settle.transaction());

        return body;
    }

    private void attachSettlementHeader(ServerHttpResponse response, FacilitatorSettleResponse settle) {
        try {
            byte[] proofBytes = objectMapper.writeValueAsBytes(settle);
            String proof = Base64.getEncoder().encodeToString(proofBytes);
            response.getHeaders().add(X_PAYMENT_RESPONSE_HEADER, proof);
        } catch (Exception e) {
            log.error("Failed to encode X-PAYMENT-RESPONSE header: {}", e.getMessage());
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
