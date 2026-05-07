package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.EvmExactPayload;
import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import com.dartintel.api.payment.dto.PaymentPayload;
import com.dartintel.api.payment.dto.PaymentRequirement;
import com.dartintel.api.payment.dto.PaymentRequirementsBody;
import com.dartintel.api.payment.dto.ResourceInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    /**
     * Marker attribute set by {@link X402SettlementAdvice#failClosed}
     * after it has explicitly released the Redis signature lock.
     * {@link #afterCompletion} reads this and skips the redundant
     * release. Pure cleanliness — the underlying Redis DEL is
     * idempotent so the second call is a no-op either way, but
     * leaving the implicit double-release in place obscures the
     * intent ("who is responsible for releasing the lock here?").
     */
    static final String REQUEST_ATTR_LOCK_RELEASED = "x402.lockReleased";
    static final int X402_VERSION = 2;
    static final BigDecimal USDC_ATOMIC = new BigDecimal(1_000_000);

    private final PaymentStore paymentStore;
    private final FacilitatorClient facilitatorClient;
    private final X402Properties props;
    private final ObjectMapper objectMapper;

    /**
     * Cache the bazaar extension shape per {@link X402Paywall} mode
     * — the structure is fully determined by the annotation, so a
     * single instance is reused for every 402 challenge of a given
     * pricing mode. Eliminates seven nested {@code Map.of} / {@code
     * List.of} allocations on every uncached challenge.
     *
     * <p>Keys: {@link X402Paywall.Mode#FIXED} or
     * {@link X402Paywall.Mode#PER_RESULT}. Built lazily by
     * {@link #buildBazaarExtension(X402Paywall)} on first use; this
     * works because the annotation values for a given mode are
     * config-time constants in our codebase (the only fields that
     * matter are the mode itself, the count param name, the default
     * count, the max count, and the unit price — all stable per
     * controller method, all stable across the JVM lifetime).
     */
    private final java.util.concurrent.ConcurrentMap<X402Paywall.Mode, Map<String, Object>>
            cachedBazaarByMode = new java.util.concurrent.ConcurrentHashMap<>();

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

        // Standard rate-limit advisory headers on every paid endpoint
        // response (whether the path is taking the 402, 400, or 200
        // branch). The values reflect the upstream-bound rate limit on
        // the cold-cache path: Gemini Flash-Lite is configured at
        // 10 RPM via the `gemini` Resilience4j RateLimiter, which is
        // the binding constraint for first-time-paid rcpt_nos. Cache
        // hits are not subject to this limit (the LLM is never
        // touched), but the conservative ceiling lets agents schedule
        // safely without distinguishing cold vs warm paths up front.
        // The 30 RPM `dart-document` budget is looser, so we surface
        // gemini's 10 RPM as the canonical ceiling. Header conventions
        // follow the de-facto X-RateLimit-* trio (Limit, Remaining,
        // Reset) used by GitHub, Stripe, and most public APIs — every
        // mature HTTP client library already knows how to read them.
        // Remaining is approximated rather than proxied from the
        // RateLimiter directly because Resilience4j's available-permit
        // count is window-relative and not meaningful per-request.
        response.setHeader("X-RateLimit-Limit", "10");
        response.setHeader("X-RateLimit-Window-Seconds", "60");
        response.setHeader("X-RateLimit-Scope",
                "upstream-cold-path-bound (Gemini 10 RPM); cache hits unbounded");

        // Pre-paywall required-param check. Without this, a missing
        // required query param (e.g. /v1/disclosures/by-ticker without
        // `ticker`) produces a 402 at the default price, the agent
        // signs an EIP-3009 authorisation against it, retries — and
        // only then does the controller's @RequestParam binding fail
        // with 400. The on-chain settle is skipped, but the agent has
        // burned an EIP-3009 nonce for nothing. Short-circuit here so
        // the agent gets a 400 BEFORE committing to signing.
        for (String required : paywall.requiredQueryParams()) {
            String value = request.getParameter(required);
            if (value == null || value.isBlank()) {
                writeBadRequest(response,
                        "missing_parameter",
                        "required parameter '" + required + "' is missing",
                        missingParamHintForPaidEndpoint(required));
                return false;
            }
        }

        // Pre-flight count validation for PER_RESULT pricing. Without
        // this, ?limit=abc / ?limit=0 / ?limit=999 emits a 402 at a
        // silently-defaulted/clamped price; the agent signs an
        // EIP-3009 authorisation against that price and only then
        // learns the controller's @Min(1)@Max(50) rejects with 400 —
        // the same nonce-burning UX the round-12 required-param fix
        // already closed. Apply the same pre-flight here so a bad
        // limit gets a 400 BEFORE the agent commits to signing.
        if (paywall.pricingMode() == X402Paywall.Mode.PER_RESULT
                && !paywall.countQueryParam().isBlank()) {
            String countParam = paywall.countQueryParam();
            String raw = request.getParameter(countParam);
            if (raw != null && !raw.isBlank()) {
                Integer parsed;
                try {
                    parsed = Integer.valueOf(raw.trim());
                } catch (NumberFormatException ignored) {
                    parsed = null;
                }
                if (parsed == null) {
                    writeBadRequest(response,
                            "validation_failed",
                            countParam + " must be an integer (got '" + raw + "')",
                            countParamHint(countParam, paywall));
                    return false;
                }
                if (parsed < 1 || parsed > paywall.maxCount()) {
                    writeBadRequest(response,
                            "validation_failed",
                            countParam + " is out of range [1, " + paywall.maxCount()
                                    + "] (got " + parsed + ")",
                            countParamHint(countParam, paywall));
                    return false;
                }
            }
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
            writePaymentRequired(response, resourceUrl, requirement, paywall, "Payment required");
            return false;
        }

        // Decode FIRST so the replay key can be the EIP-3009
        // authorisation nonce — the canonical replay anchor per x402
        // v2 §10.1 ("EIP-3009 contracts inherently prevent nonce
        // reuse at the smart contract level"). A raw-header SHA-256
        // would also work but is sensitive to base64 padding /
        // whitespace / JSON key ordering, so two functionally
        // identical payloads could pass the SETNX guard and both go
        // to the facilitator (the on-chain layer rejects the second
        // one anyway, but we waste a verify round-trip). Anchoring
        // on the nonce keeps replay protection idempotent across
        // encoding variation.
        PaymentPayload paymentPayload;
        try {
            byte[] decoded = Base64.getDecoder().decode(paymentHeader);
            paymentPayload = objectMapper.readValue(decoded, PaymentPayload.class);
        } catch (Exception e) {
            // Spec maps a malformed payment payload to HTTP 400, not 402
            // (transports-v2/http.md error table: "Invalid Payment | 400 |
            // Malformed payment payload or requirements"). 402 is reserved
            // for "no payment provided" or "payment required after a settle
            // failure" — using it for garbage input prevents spec-aware
            // clients from branching on the malformed-payload code path.
            writeBadRequest(response,
                    "malformed_payment",
                    "Malformed payment payload",
                    "The PAYMENT-SIGNATURE (or legacy X-PAYMENT) header value must be " +
                            "base64-encoded JSON of an x402 PaymentPayload. The TypeScript / " +
                            "Python SDKs build this for you. See " +
                            "https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md " +
                            "for the wire shape, or GET https://api.koreafilings.com/v1/disclosures/sample " +
                            "for a free schema preview without payment.");
            return false;
        }

        // Replay key: EIP-3009 nonce when available (canonical), with
        // a fallback to the SHA-256 of the raw header for malformed-
        // but-not-rejected payloads (defence in depth).
        String sigHash = replayKey(paymentPayload, paymentHeader);
        if (!paymentStore.registerIfAbsent(sigHash)) {
            writePaymentRequired(response, resourceUrl, requirement, paywall, "Payment signature reused");
            return false;
        }

        // Resource URL binding (x402 v2 §5.1 §5.2.2). The client
        // signed for a specific resource URL; the server must refuse to
        // honour that signature on any other URL. Without this check, a
        // signature for the cheap fixed-price endpoint
        // (/v1/disclosures/summary?rcptNo=…, 0.005 USDC) could be
        // replayed against the more expensive per-result endpoint
        // (/v1/disclosures/by-ticker?ticker=…&limit=50, up to 0.25 USDC)
        // — the EIP-3009 signature itself binds amount/nonce/validity,
        // not URL, so URL binding is purely server policy. Comparison
        // is exact (including query string) because the signature was
        // computed over exactly that string.
        ResourceInfo signedResource = paymentPayload.resource();
        if (signedResource == null || signedResource.url() == null
                || !signedResource.url().equals(resourceUrl)) {
            paymentStore.release(sigHash);
            String signedUrl = signedResource != null ? signedResource.url() : "(missing)";
            log.warn("x402 resource URL mismatch: signed={} actual={}", signedUrl, resourceUrl);
            writePaymentRequired(response, resourceUrl, requirement, paywall,
                    "Resource URL mismatch — signature scoped to a different endpoint");
            return false;
        }

        FacilitatorVerifyResponse verifyResp;
        try {
            verifyResp = facilitatorClient.verify(
                    new FacilitatorVerifyRequest(X402_VERSION, paymentPayload, requirement));
        } catch (Exception e) {
            log.error("Facilitator verify call failed: {}", e.getMessage());
            paymentStore.release(sigHash);
            writePaymentRequired(response, resourceUrl, requirement, paywall,
                    "Facilitator unavailable");
            return false;
        }

        if (!verifyResp.isValid()) {
            paymentStore.release(sigHash);
            String reason = sanitiseLogValue(verifyResp.invalidReason());
            writePaymentRequired(response, resourceUrl, requirement, paywall,
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

        // X402SettlementAdvice.failClosed flips the response to 402
        // and explicitly releases the Redis signature itself; we
        // skip the redundant release here so the locking
        // responsibility is unambiguous.
        if (Boolean.TRUE.equals(request.getAttribute(REQUEST_ATTR_LOCK_RELEASED))) {
            return;
        }

        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            paymentStore.release(verified.signatureHash());
            log.info("x402 release: status={} sigHash={}", status, shortHash(verified.signatureHash()));
        }
        // 2xx settlement is handled by X402SettlementAdvice so the
        // PAYMENT-RESPONSE header can reach the client before the
        // body is committed.
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

    /**
     * Emit HTTP 400 with a small JSON envelope for malformed payment
     * payloads. Per the x402 v2 transport spec error table, 400 is
     * the correct status for an unparseable {@code PAYMENT-SIGNATURE}
     * header — clients that send garbage need to know they sent
     * garbage rather than think they were charged or rate-limited.
     */
    /**
     * Write a 400 envelope with the same shape that
     * {@link com.dartintel.api.api.ApiExceptionHandler} emits for
     * Bean-Validation and missing-param failures further down the
     * stack. {@code error} is a stable machine-readable code,
     * {@code message} is the human-facing description, and
     * {@code agentActionHint} is the path-aware "what would unblock
     * me" instruction the agent can act on without reading prose
     * docs. Round-12 unified the two surfaces so a self-correcting
     * client sees one consistent envelope across both paywall-
     * preflight and post-controller validation failures.
     */
    private void writeBadRequest(HttpServletResponse response, String errorCode,
                                 String message, String agentActionHint) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Per-request error envelope — never cache.
        response.setHeader("Cache-Control", "no-store");
        try {
            java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("error", errorCode);
            body.put("message", message);
            body.put("agent_action_hint", agentActionHint);
            response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise 400 envelope: {}", e.getMessage());
        }
    }

    /**
     * Path-aware hint for "you forgot a required query param on a
     * paid endpoint." Mirrors the messages the
     * {@link com.dartintel.api.api.ApiExceptionHandler} would emit
     * for a free endpoint, so a self-correcting agent can recover
     * from either branch without distinguishing them.
     */
    private static String missingParamHintForPaidEndpoint(String paramName) {
        if ("rcptNo".equals(paramName)) {
            return "Append ?rcptNo={14-digit DART receipt number}. Get a valid value " +
                    "from GET /v1/disclosures/recent (free) or from a previous " +
                    "/v1/disclosures/by-ticker response. " +
                    "GET /v1/disclosures/sample shows the response shape without paying.";
        }
        if ("ticker".equals(paramName)) {
            return "Append ?ticker={6-or-7-digit-KRX-ticker} (e.g. 005930 for Samsung Electronics). " +
                    "Resolve a name to a ticker via GET /v1/companies?q={name} (free). " +
                    "GET /v1/disclosures/sample shows the per-row response shape without paying.";
        }
        return "See https://api.koreafilings.com/v1/pricing for required parameters per endpoint.";
    }

    /**
     * Hint for "your count multiplier was the wrong shape". Names the
     * exact integer range the server clamps against so the agent can
     * fix the request and retry without burning round-trips, and
     * points at GET /v1/pricing for the canonical bounds (which are
     * already in the {@code maxCount} field of each PaidEndpoint).
     */
    private static String countParamHint(String countParam, X402Paywall paywall) {
        return "Set ?" + countParam + "={integer in [1, " + paywall.maxCount()
                + "]}; default is " + paywall.defaultCount() + ". "
                + "GET /v1/pricing.endpoints[].requiredParams describes the bounds for every "
                + "paid endpoint. Larger values would cause the 402 challenge to advertise a "
                + "price the controller would later reject — fixing the request shape now "
                + "avoids signing an EIP-3009 authorisation against the wrong amount.";
    }

    private void writePaymentRequired(HttpServletResponse response, String resourceUrl,
                                      PaymentRequirement requirement, X402Paywall paywall,
                                      String error) throws IOException {
        String description = paywall.description();
        PaymentRequirementsBody body = new PaymentRequirementsBody(
                X402_VERSION,
                error,
                new ResourceInfo(resourceUrl,
                        description == null || description.isBlank() ? null : description,
                        MediaType.APPLICATION_JSON_VALUE),
                List.of(requirement),
                Map.of("bazaar", cachedBazaarByMode.computeIfAbsent(
                        paywall.pricingMode(), m -> buildBazaarExtension(paywall)))
        );
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Per-request challenge — error string and effective amount
        // both depend on the request shape, so an intermediate cache
        // (Cloudflare, agent-side proxy) re-serving a stale 402 would
        // surface a misleading reason or amount.
        response.setHeader("Cache-Control", "no-store");
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
     * <p>Both paid endpoints take their inputs as query parameters. The
     * bazaar v1 input schema only has buckets for {@code queryParams},
     * {@code bodyFields}, and {@code headerFields} — path parameters
     * are silently dropped by x402scan's OpenAPI → bazaar translation,
     * leaving the endpoint discoverable but un-callable from raw
     * discovery. Declaring real query params here is what makes the
     * endpoint actually usable from a cold-start agent.
     *
     * <p>The set of query params is derived from the {@link X402Paywall}
     * annotation's pricing mode rather than from the resource URL —
     * {@code PER_RESULT} mode implies a {@code countParam} (currently
     * {@code limit}) plus a per-resource selector ({@code ticker});
     * {@code FIXED} mode implies a per-resource selector
     * ({@code rcptNo}). Driving this from the annotation keeps the
     * declaration in sync with the paywall config without depending
     * on URL substring heuristics.
     */
    private Map<String, Object> buildBazaarExtension(X402Paywall paywall) {
        Map<String, Object> queryParams;
        if (paywall.pricingMode() == X402Paywall.Mode.PER_RESULT) {
            // PER_RESULT — by-ticker shape. Selector is `ticker`,
            // multiplier is `paywall.countQueryParam()` (default `limit`).
            queryParams = Map.of(
                    "ticker", Map.of(
                            "type", "string",
                            "required", true,
                            "description",
                                    "Six-digit KRX ticker (e.g. 005930 for Samsung Electronics). " +
                                    "Resolve from a company name first via the free " +
                                    "/v1/companies?q={name} endpoint."),
                    paywall.countQueryParam(), Map.of(
                            "type", "integer",
                            "required", false,
                            "description",
                                    "Max filings to return (1–" + paywall.maxCount()
                                    + ", default " + paywall.defaultCount() + "). "
                                    + "Final price is " + paywall.priceUsdc()
                                    + " × " + paywall.countQueryParam() + " USDC. "
                                    + "Note: the agent is charged for "
                                    + paywall.countQueryParam() + ", not for the actual "
                                    + "row count returned. A ticker with fewer recent "
                                    + "filings than " + paywall.countQueryParam()
                                    + " still costs the full amount; pre-filter via the "
                                    + "free /v1/disclosures/recent feed if budget is "
                                    + "tight. The response body's `delivered` field "
                                    + "shows the actual count and `chargedFor` echoes "
                                    + "the price multiplier."));
        } else {
            // FIXED — summary shape. Selector is `rcptNo`.
            queryParams = Map.of(
                    "rcptNo", Map.of(
                            "type", "string",
                            "required", true,
                            "description",
                                    "14-digit DART receipt number (e.g. 20260424900874). " +
                                    "Obtain from the free /v1/disclosures/recent feed or " +
                                    "from the by-ticker response. Receipt numbers are " +
                                    "not LLM-knowable and must always be looked up first."));
        }
        Map<String, Object> input = Map.of(
                "type", "http",
                "method", "GET",
                "queryParams", queryParams
        );
        // The 200-response example must match the actual body shape.
        // PER_RESULT (by-ticker) wraps the summaries in a batch
        // envelope with chargedFor / delivered / count; FIXED
        // (summary) returns the bare DisclosureSummaryDto. An agent
        // parsing the bazaar example must see the same field
        // structure as what arrives in the 200, otherwise they read
        // top-level `summaryEn` and miss the wrapper.
        Map<String, Object> singleSummary = Map.of(
                "rcptNo", "20260424900874",
                "summaryEn", "AI-generated English summary of the disclosure.",
                "importanceScore", 7,
                "eventType", "OTHER",
                "sectorTags", List.of("Capital Goods"),
                "tickerTags", List.of("095440"),
                "actionableFor", List.of("traders"),
                "generatedAt", "2026-04-24T09:00:00Z"
        );
        Map<String, Object> outputExample = paywall.pricingMode() == X402Paywall.Mode.PER_RESULT
                ? Map.of(
                        "ticker", "005930",
                        "chargedFor", paywall.defaultCount(),
                        "delivered", 1,
                        "count", 1,
                        "summaries", List.of(singleSummary))
                : singleSummary;
        Map<String, Object> output = Map.of(
                "type", "json",
                "example", outputExample
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
                    String rawValue = pair.substring(eq + 1);
                    // URL-decode in case an upstream proxy or caller
                    // percent-encoded the digits or used `+`. Bare
                    // 14-digit numerics never need decoding, but
                    // defensive decoding makes the function robust to
                    // future endpoints whose rcptNo could carry
                    // non-numeric chars.
                    String value = java.net.URLDecoder.decode(
                            rawValue, java.nio.charset.StandardCharsets.UTF_8);
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

    /**
     * Pick the replay-protection key for a parsed payment payload.
     * Prefers the EIP-3009 authorisation {@code nonce} (32-byte
     * hex string, on-chain-canonical) when present; falls back to
     * a SHA-256 of the raw base64 header when the payload's
     * payment-method shape doesn't expose a nonce.
     */
    /**
     * Strip the full C0 control range, DEL, and the ANSI escape
     * introducer from a string before it lands in a structured log
     * line — defends against log forging where a malicious or
     * compromised facilitator returns a multi-line {@code invalidReason}
     * that appears as forged log entries, ANSI-coloured fake errors
     * in terminal log viewers, or null bytes that corrupt
     * structured-log JSON ingestion (Logstash, Loki, etc.).
     *
     * <p>The earlier shape only stripped {@code \r}, {@code \n}, and
     * {@code \t}, leaving {@code \x1b[31m...\x1b[0m} ANSI sequences
     * and {@code \0} null bytes intact — both of which an attacker
     * with control over a facilitator response can use to forge
     * apparent admin-level log lines or break log shippers.
     */
    static String sanitiseLogValue(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        // Replaces:
        //   \x00-\x1f — full C0 control range (CR, LF, TAB, NUL,
        //               SOH, STX, ETX, BEL, BS, VT, FF, SO, SI,
        //               DLE, DC1-4, NAK, SYN, ETB, CAN, EM, SUB,
        //               ESC, FS, GS, RS, US — covers the ANSI
        //               escape introducer at 0x1B as well as raw
        //               nulls)
        //   \x7f      — DEL
        // Replacement is space rather than empty so token positions
        // in the log line stay stable for grep / awk parsers.
        return value.replaceAll("[\\x00-\\x1f\\x7f]", " ");
    }

    static String replayKey(PaymentPayload payload, String rawHeader) {
        if (payload != null && payload.payload() != null
                && payload.payload().authorization() != null
                && payload.payload().authorization().nonce() != null
                && !payload.payload().authorization().nonce().isBlank()) {
            return "nonce:" + payload.payload().authorization().nonce();
        }
        return "raw:" + sha256Hex(rawHeader);
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
