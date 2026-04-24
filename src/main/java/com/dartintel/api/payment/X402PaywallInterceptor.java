package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.EvmExactPayload;
import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import com.dartintel.api.payment.dto.PaymentPayload;
import com.dartintel.api.payment.dto.PaymentRequirement;
import com.dartintel.api.payment.dto.PaymentRequirementsBody;
import com.dartintel.api.payment.dto.ResourceInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gates any controller method annotated with {@link X402Paywall}. The
 * preHandle phase performs the x402 verification + replay-protection
 * sequence and writes a 402 body with payment requirements when it
 * rejects a request. The afterCompletion phase settles against the
 * facilitator if the controller produced a 2xx response, releasing the
 * Redis replay guard otherwise so the client can retry without
 * double-paying.
 *
 * The X-PAYMENT-RESPONSE header that the spec recommends lands in Day 2
 * via a ResponseBodyAdvice, since HandlerInterceptor cannot set
 * response headers after the body has started streaming for
 * @ResponseBody handlers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class X402PaywallInterceptor implements HandlerInterceptor {

    static final String X_PAYMENT_HEADER = "X-PAYMENT";
    static final String REQUEST_ATTR_VERIFIED = "x402.verifiedPayment";
    private static final int X402_VERSION = 2;
    private static final BigDecimal USDC_ATOMIC = new BigDecimal(1_000_000);

    private final PaymentStore paymentStore;
    private final FacilitatorClient facilitatorClient;
    private final PaymentLogRepository paymentLogRepository;
    private final X402Properties props;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        X402Paywall paywall = handlerMethod.getMethodAnnotation(X402Paywall.class);
        if (paywall == null) {
            return true;
        }

        String resourceUrl = request.getRequestURL().toString();
        PaymentRequirement requirement = buildRequirement(paywall.priceUsdc());

        String xPayment = request.getHeader(X_PAYMENT_HEADER);
        if (xPayment == null || xPayment.isBlank()) {
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(), "Payment required");
            return false;
        }

        String sigHash = sha256Hex(xPayment);
        if (!paymentStore.registerIfAbsent(sigHash)) {
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(), "Payment signature reused");
            return false;
        }

        PaymentPayload paymentPayload;
        try {
            byte[] decoded = Base64.getDecoder().decode(xPayment);
            paymentPayload = objectMapper.readValue(decoded, PaymentPayload.class);
        } catch (Exception e) {
            paymentStore.release(sigHash);
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(),
                    "Malformed X-PAYMENT header");
            return false;
        }

        FacilitatorVerifyResponse verifyResp;
        try {
            verifyResp = facilitatorClient.verify(
                    new FacilitatorVerifyRequest(X402_VERSION, paymentPayload, requirement));
        } catch (Exception e) {
            log.error("Facilitator verify call failed: {}", e.getMessage());
            paymentStore.release(sigHash);
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(),
                    "Facilitator unavailable");
            return false;
        }

        if (!verifyResp.isValid()) {
            paymentStore.release(sigHash);
            String reason = verifyResp.invalidReason() != null ? verifyResp.invalidReason() : "unknown";
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(),
                    "Payment rejected: " + reason);
            return false;
        }

        request.setAttribute(REQUEST_ATTR_VERIFIED,
                new VerifiedPayment(sigHash, paymentPayload, requirement,
                        verifyResp.payer(), request.getRequestURI()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object attr = request.getAttribute(REQUEST_ATTR_VERIFIED);
        if (!(attr instanceof VerifiedPayment verified)) {
            return;
        }

        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            paymentStore.release(verified.signatureHash());
            log.info("x402 release: status={} sigHash={}", status, shortHash(verified.signatureHash()));
            return;
        }

        FacilitatorSettleResponse settle;
        try {
            settle = facilitatorClient.settle(new FacilitatorSettleRequest(
                    X402_VERSION, verified.payload(), verified.requirement()));
        } catch (Exception e) {
            log.error("x402 settle exception for endpoint={}: {}", verified.endpoint(), e.getMessage(), e);
            return;
        }

        if (settle.success()) {
            persistPaymentLog(verified, settle);
            log.info("x402 settled: endpoint={} payer={} amount={} tx={}",
                    verified.endpoint(), verified.payer(),
                    verified.requirement().amount(), settle.transaction());
        } else {
            log.error("x402 settle rejected: reason={} endpoint={}", settle.errorReason(), verified.endpoint());
        }
    }

    private PaymentRequirement buildRequirement(String priceUsdc) {
        BigDecimal human = new BigDecimal(priceUsdc);
        BigDecimal atomic = human.multiply(USDC_ATOMIC).setScale(0, RoundingMode.HALF_UP);
        return new PaymentRequirement(
                "exact",
                props.network(),
                atomic.toPlainString(),
                props.asset(),
                props.recipientAddress(),
                props.maxTimeoutSeconds(),
                Map.of("name", "USDC", "version", "2")
        );
    }

    private void writePaymentRequired(HttpServletResponse response, String resourceUrl,
                                      PaymentRequirement requirement, String description,
                                      String error) throws IOException {
        PaymentRequirementsBody body = new PaymentRequirementsBody(
                X402_VERSION,
                error,
                new ResourceInfo(resourceUrl,
                        description == null || description.isBlank() ? null : description,
                        MediaType.APPLICATION_JSON_VALUE),
                List.of(requirement)
        );
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    private void persistPaymentLog(VerifiedPayment verified, FacilitatorSettleResponse settle) {
        String rcptNo = extractRcptNo(verified.endpoint());
        BigDecimal amountHuman = new BigDecimal(verified.requirement().amount())
                .divide(USDC_ATOMIC, 6, RoundingMode.HALF_UP);
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

    /**
     * Pulls the 14-digit rcpt_no out of a URI like
     * {@code /v1/disclosures/20260423000001/summary}. Returns null for
     * endpoints that are not per-filing (e.g. {@code /v1/disclosures/latest}).
     */
    static String extractRcptNo(String uri) {
        int idx = uri.indexOf("/disclosures/");
        if (idx < 0) {
            return null;
        }
        String tail = uri.substring(idx + "/disclosures/".length());
        int slash = tail.indexOf('/');
        String candidate = slash >= 0 ? tail.substring(0, slash) : tail;
        return candidate.length() == 14 && candidate.chars().allMatch(Character::isDigit)
                ? candidate : null;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String shortHash(String hash) {
        return hash.length() >= 8 ? hash.substring(0, 8) + "…" : hash;
    }

    private record VerifiedPayment(
            String signatureHash,
            PaymentPayload payload,
            PaymentRequirement requirement,
            String payer,
            String endpoint
    ) {
    }

    /** Suppress unused import warning for EvmExactPayload (referenced via PaymentPayload). */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IMPORT = EvmExactPayload.class;
}
