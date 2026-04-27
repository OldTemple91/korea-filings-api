package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.EvmExactPayload;
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
 * Gates any controller method annotated with {@link X402Paywall}.
 * preHandle enforces the full x402 pre-flight (payment presence, Redis
 * replay guard, facilitator verify) and writes a 402 body whenever it
 * rejects. Settlement + PaymentLog persistence live in
 * {@link X402SettlementAdvice}, which needs to fire BEFORE the
 * response body is written so it can attach X-PAYMENT-RESPONSE.
 * afterCompletion only runs one side-effect: release the Redis
 * signature when the controller produced a non-2xx so the client can
 * retry without double-paying.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class X402PaywallInterceptor implements HandlerInterceptor {

    static final String X_PAYMENT_HEADER = "X-PAYMENT";
    /**
     * x402 v2 transport spec
     * (specs/transports-v2/http.md) moves the payment requirements off
     * the body into a base64-encoded {@code PAYMENT-REQUIRED} response
     * header. We continue to emit the JSON body so v1 clients (our own
     * Python SDK, the public x402.org facilitator) keep working, but
     * v2 indexers like x402scan only inspect the header — without it
     * they reject the resource as "No valid x402 response found".
     */
    static final String PAYMENT_REQUIRED_HEADER = "PAYMENT-REQUIRED";
    static final String REQUEST_ATTR_VERIFIED = "x402.verifiedPayment";
    static final int X402_VERSION = 2;
    static final BigDecimal USDC_ATOMIC = new BigDecimal(1_000_000);

    private final PaymentStore paymentStore;
    private final FacilitatorClient facilitatorClient;
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
        }
        // 2xx settlement is handled by X402SettlementAdvice so X-PAYMENT-RESPONSE
        // can reach the client before the body is committed.
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
                List.of(requirement),
                Map.of("bazaar", buildBazaarExtension(resourceUrl))
        );
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Dual-emit: v2 transport spec puts the payment requirements in
        // a base64-encoded header, while v1 clients keep reading the
        // JSON body. Both contain the same PaymentRequired object so
        // clients on either side of the transport upgrade succeed.
        byte[] serialised = objectMapper.writeValueAsBytes(body);
        response.setHeader(PAYMENT_REQUIRED_HEADER,
                Base64.getEncoder().encodeToString(serialised));
        response.getOutputStream().write(serialised);
    }

    /**
     * Build the {@code bazaar} extension payload that x402scan and other
     * indexers require in strict mode. The extension declares the
     * endpoint's invocation shape (HTTP method, parameters) and a sample
     * output so AI agents can call the endpoint without prior schema
     * negotiation.
     *
     * <p>Our paid summary endpoint takes a path parameter (rcptNo) and
     * no query string or body. Path parameters are not first-class in
     * the bazaar spec — the canonical way to communicate them is to
     * publish a concrete sample URL in the resources list (the
     * {@code /.well-known/x402} document already does this) so an agent
     * can probe a real 402 with a real path. The {@code queryParams}
     * field stays empty.
     */
    private Map<String, Object> buildBazaarExtension(String resourceUrl) {
        Map<String, Object> input = Map.of(
                "type", "http",
                "method", "GET",
                "queryParams", Map.of()
        );
        Map<String, Object> output = Map.of(
                "type", "json",
                "example", Map.of(
                        "rcptNo", "20260424900874",
                        "summaryEn", "AI-generated English summary of the disclosure.",
                        "importanceScore", 7,
                        "eventType", "OTHER",
                        "sectorTags", List.of("Capital Goods"),
                        "tickerTags", List.of("095440"),
                        "actionableFor", List.of("traders"),
                        "generatedAt", "2026-04-24T09:00:00Z"
                )
        );
        Map<String, Object> info = Map.of(
                "input", input,
                "output", output
        );
        Map<String, Object> schema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "type", Map.of("type", "string", "const", "http"),
                                        "method", Map.of("type", "string", "enum", List.of("GET", "HEAD", "DELETE")),
                                        "queryParams", Map.of("type", "object")
                                ),
                                "required", List.of("type", "method")
                        ),
                        "output", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "type", Map.of("type", "string"),
                                        "example", Map.of("type", "object")
                                ),
                                "required", List.of("type")
                        )
                ),
                "required", List.of("input")
        );
        return Map.of(
                "info", info,
                "schema", schema
        );
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

    /** Suppress unused import warning for EvmExactPayload (referenced via PaymentPayload). */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IMPORT = EvmExactPayload.class;
}
