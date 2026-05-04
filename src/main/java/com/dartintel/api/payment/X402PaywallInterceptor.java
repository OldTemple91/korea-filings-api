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
 * response body is written so it can attach the settlement proof
 * header. afterCompletion only runs one side-effect: release the Redis
 * signature when the controller produced a non-2xx so the client can
 * retry without double-paying.
 *
 * <h3>Header naming</h3>
 *
 * <p>The x402 v2 transport spec
 * (<a href="https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md">
 * specs/transports-v2/http.md</a>) moved away from the legacy
 * {@code X-*} prefix:
 * <ul>
 *   <li>Server → client challenge: {@code PAYMENT-REQUIRED} (was
 *       carried in the body as JSON in v1).</li>
 *   <li>Client → server payment: {@code PAYMENT-SIGNATURE} (was
 *       {@code X-PAYMENT}).</li>
 *   <li>Server → client settlement: {@code PAYMENT-RESPONSE} (was
 *       {@code X-PAYMENT-RESPONSE}; emitted by
 *       {@link X402SettlementAdvice}).</li>
 * </ul>
 *
 * <p>The interceptor reads the v2 header first and falls back to the
 * legacy {@code X-PAYMENT} header so existing SDK / MCP releases
 * (koreafilings 0.2.x, koreafilings-mcp 0.2.x) keep working during
 * transition. The 402 body still carries the JSON requirements for
 * the same reason. Both shapes will be re-evaluated for removal once
 * 0.3.x adoption is observable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class X402PaywallInterceptor implements HandlerInterceptor {

    /** v2 transport — preferred client-to-server payment header. */
    static final String PAYMENT_SIGNATURE_HEADER = "PAYMENT-SIGNATURE";
    /** v1 compat — accepted but not advertised. */
    static final String LEGACY_X_PAYMENT_HEADER = "X-PAYMENT";
    /** v2 transport — server-to-client 402 challenge header. */
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

        String resourceUrl = buildResourceUrl(request);
        String effectivePriceUsdc = computeEffectivePrice(paywall, request);
        PaymentRequirement requirement = buildRequirement(effectivePriceUsdc);

        // v2 spec uses PAYMENT-SIGNATURE; X-PAYMENT is v1 legacy that
        // we keep accepting until 0.3.x SDK / MCP fully replaces 0.2.x
        // in the wild. Read v2 first so a client that sends both wins
        // on the spec-compliant value.
        String paymentHeader = request.getHeader(PAYMENT_SIGNATURE_HEADER);
        if (paymentHeader == null || paymentHeader.isBlank()) {
            paymentHeader = request.getHeader(LEGACY_X_PAYMENT_HEADER);
        }
        if (paymentHeader == null || paymentHeader.isBlank()) {
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(), "Payment required");
            return false;
        }

        String sigHash = sha256Hex(paymentHeader);
        if (!paymentStore.registerIfAbsent(sigHash)) {
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(), "Payment signature reused");
            return false;
        }

        PaymentPayload paymentPayload;
        try {
            byte[] decoded = Base64.getDecoder().decode(paymentHeader);
            paymentPayload = objectMapper.readValue(decoded, PaymentPayload.class);
        } catch (Exception e) {
            paymentStore.release(sigHash);
            writePaymentRequired(response, resourceUrl, requirement, paywall.description(),
                    "Malformed payment header");
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

        // Endpoint stored on the verified payment includes the query
        // string so payment_log rows preserve the request shape (e.g.
        // `?rcptNo=...`, `?ticker=...&limit=...`) and so
        // extractRcptNo can pull the rcpt_no out for per-filing
        // settlements.
        String endpointForLog = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            endpointForLog = endpointForLog + "?" + query;
        }
        request.setAttribute(REQUEST_ATTR_VERIFIED,
                new VerifiedPayment(sigHash, paymentPayload, requirement,
                        verifyResp.payer(), endpointForLog));
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

    /**
     * Resolve the actual price for this request based on the annotation's
     * {@link X402Paywall#pricingMode()}. Per-result endpoints multiply
     * the unit price by an integer query parameter (e.g. {@code limit})
     * so a single 0.005-per-summary annotation can charge correctly for
     * any batch size. The multiplier is clamped at the annotation's
     * {@code maxCount} as defence-in-depth even if the controller's
     * Bean Validation already rejects out-of-range requests.
     */
    private static String computeEffectivePrice(X402Paywall paywall, HttpServletRequest request) {
        if (paywall.pricingMode() != X402Paywall.Mode.PER_RESULT) {
            return paywall.priceUsdc();
        }
        int count = paywall.defaultCount();
        String raw = request.getParameter(paywall.countQueryParam());
        if (raw != null) {
            try {
                count = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                count = paywall.defaultCount();
            }
        }
        if (count < 1) count = 1;
        if (count > paywall.maxCount()) count = paywall.maxCount();
        BigDecimal unit = new BigDecimal(paywall.priceUsdc());
        return unit.multiply(BigDecimal.valueOf(count))
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Build the canonical resource URL for the 402 challenge so the
     * payment signature commits to a deterministic resource string.
     * {@link HttpServletRequest#getRequestURL()} drops the query, but
     * for query-param-driven endpoints (where {@code ?ticker=…} or
     * {@code ?rcptNo=…} carries the input) the query string is part
     * of the resource and must be in the signed payload — otherwise
     * the same signature would be valid for {@code ?ticker=A} and
     * {@code ?ticker=B}, breaking the per-resource binding x402
     * relies on.
     */
    private static String buildResourceUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(request.getRequestURL());
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }
        return url.toString();
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
                Map.of(
                        "name", props.tokenName() != null ? props.tokenName() : "USDC",
                        "version", props.tokenVersion() != null ? props.tokenVersion() : "2"
                )
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
     * <p>Both paid endpoints take their inputs as query parameters
     * ({@code rcptNo} for summary, {@code ticker} + {@code limit} for
     * by-ticker). The bazaar v1 input schema only has buckets for
     * {@code queryParams}, {@code bodyFields}, and {@code headerFields}
     * — path parameters are silently dropped by x402scan's
     * OpenAPI → bazaar translation, leaving the endpoint discoverable
     * but un-callable from raw discovery. Declaring real query params
     * here is what makes the endpoint actually usable from a
     * cold-start agent.
     */
    private Map<String, Object> buildBazaarExtension(String resourceUrl) {
        boolean isByTicker = resourceUrl.contains("/by-ticker");
        Map<String, Object> queryParams = isByTicker
                ? Map.of(
                        "ticker", Map.of(
                                "type", "string",
                                "required", true,
                                "description",
                                        "Six-digit KRX ticker (e.g. 005930 for Samsung Electronics). " +
                                        "Resolve from a company name first via the free " +
                                        "/v1/companies?q={name} endpoint."),
                        "limit", Map.of(
                                "type", "integer",
                                "required", false,
                                "description",
                                        "Max filings to return (1–50, default 5). " +
                                        "Final price is 0.005 × limit USDC."))
                : Map.of(
                        "rcptNo", Map.of(
                                "type", "string",
                                "required", true,
                                "description",
                                        "14-digit DART receipt number (e.g. 20260424900874). " +
                                        "Obtain from the free /v1/disclosures/recent feed or " +
                                        "from the by-ticker response. Receipt numbers are " +
                                        "not LLM-knowable and must always be looked up first."));
        Map<String, Object> input = Map.of(
                "type", "http",
                "method", "GET",
                "queryParams", queryParams
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
     * Pulls the 14-digit {@code rcptNo} out of a paid-endpoint URL,
     * accepting either a query-param form
     * ({@code /v1/disclosures/summary?rcptNo=20260423000001}, the
     * current shape) or the legacy path-param form
     * ({@code /v1/disclosures/20260423000001/summary}, which earlier
     * 0.2.x SDK / MCP releases sent). Used by
     * {@link X402SettlementAdvice#persistPaymentLog} to denormalise
     * the receipt number alongside the settlement row. Returns null
     * for endpoints that are not per-filing (e.g. by-ticker batches).
     */
    static String extractRcptNo(String uri) {
        int q = uri.indexOf('?');
        String pathPart = q >= 0 ? uri.substring(0, q) : uri;
        String queryPart = q >= 0 ? uri.substring(q + 1) : "";

        if (!queryPart.isEmpty()) {
            for (String pair : queryPart.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                if ("rcptNo".equals(pair.substring(0, eq))) {
                    String value = pair.substring(eq + 1);
                    return isFourteenDigit(value) ? value : null;
                }
            }
        }

        int idx = pathPart.indexOf("/disclosures/");
        if (idx < 0) {
            return null;
        }
        String tail = pathPart.substring(idx + "/disclosures/".length());
        int slash = tail.indexOf('/');
        String candidate = slash >= 0 ? tail.substring(0, slash) : tail;
        return isFourteenDigit(candidate) ? candidate : null;
    }

    private static boolean isFourteenDigit(String s) {
        return s != null && s.length() == 14 && s.chars().allMatch(Character::isDigit);
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
