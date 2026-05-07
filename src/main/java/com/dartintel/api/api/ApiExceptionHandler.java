package com.dartintel.api.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates Bean Validation failures into HTTP 400 with a small
 * machine-readable envelope, instead of letting Spring Boot's default
 * handling surface a 500 with a stack trace. The convention matches
 * the {@link com.dartintel.api.payment.X402PaywallInterceptor}'s
 * malformed-payload envelope so clients can parse one error shape
 * across the whole {@code /v1/**} surface.
 *
 * <p>{@code @RequestParam} / {@code @PathVariable} constraints
 * declared on a class-level {@code @Validated} controller throw
 * {@link ConstraintViolationException}; {@code @RequestBody}-bound
 * Bean Validation throws {@link MethodArgumentNotValidException}.
 * Both should land on 400 with the offending field listed.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation_failed");
        body.put("message", ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; ")));
        body.put("agent_action_hint", validationHint(request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation_failed");
        body.put("message", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; ")));
        body.put("agent_action_hint", validationHint(request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "missing_parameter");
        body.put("message", "required parameter '" + ex.getParameterName() + "' is missing");
        body.put("agent_action_hint", missingParamHint(ex.getParameterName(), request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Build a path-aware "what would unblock the agent next" string
     * for validation failures. Cuts the gap an agent would otherwise
     * have to bridge by parsing the {@code message} field — which is
     * a fielderror like "rcptNo must be exactly 14 digits" — and
     * inferring whether it should re-call a free discovery endpoint
     * to obtain a valid value. The hints below name the free
     * endpoint to call directly.
     */
    private static String validationHint(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/v1/disclosures/summary")) {
            return "Get a valid 14-digit rcptNo from GET /v1/disclosures/recent (free) " +
                    "or from a previous GET /v1/disclosures/by-ticker response, " +
                    "then retry. Inspect the response shape without paying via " +
                    "GET /v1/disclosures/sample.";
        }
        if (path != null && path.startsWith("/v1/disclosures/by-ticker")) {
            return "Resolve a Korean company name to a 6-digit KRX ticker first via " +
                    "GET /v1/companies?q={name} (free), then retry with the returned ticker. " +
                    "Inspect the per-row response shape without paying via " +
                    "GET /v1/disclosures/sample.";
        }
        if (path != null && path.startsWith("/v1/companies")) {
            return "The query parameter q must be a non-empty string. Try a " +
                    "Korean or English company name like 'Samsung Electronics' or '삼성전자'.";
        }
        return "See https://api.koreafilings.com/v1/pricing for the canonical " +
                "free-then-paid call sequence and required parameters per endpoint.";
    }

    private static String missingParamHint(String paramName, HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("rcptNo".equals(paramName)) {
            return "Append ?rcptNo={14-digit DART receipt number}. " +
                    "Get a valid value from GET /v1/disclosures/recent (free) or " +
                    "from a previous /v1/disclosures/by-ticker response. " +
                    "GET /v1/disclosures/sample shows the response shape without paying.";
        }
        if ("ticker".equals(paramName)) {
            return "Append ?ticker={6-or-7-digit-KRX-ticker} (e.g. 005930 for Samsung Electronics). " +
                    "Resolve a name to a ticker via GET /v1/companies?q={name} (free).";
        }
        if ("q".equals(paramName)) {
            return "Append ?q={Korean or English company name}. " +
                    "Trigram fuzzy match — partial names work.";
        }
        return "See https://api.koreafilings.com/v1/pricing for required parameters per endpoint." +
                (path != null ? " (path: " + path + ")" : "");
    }

    /**
     * Redis is the source of truth for the replay guard, the summary
     * cache, the polling watermark, and the summary job queue.
     * When it's unreachable, paid endpoints cannot safely proceed
     * (no replay protection), but a 500 is the wrong shape — clients
     * have no retry signal. Surface a 503 with a short
     * {@code Retry-After} so spec-aware HTTP clients back off
     * automatically.
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Map<String, Object>> handleRedisDown(RedisConnectionFailureException ex) {
        log.error("Redis unreachable: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "service_unavailable");
        body.put("message", "Replay-guard / cache layer is temporarily unreachable. Retry shortly.");
        body.put("agent_action_hint", "Wait at least Retry-After seconds (10) before retrying " +
                "the same request. Settlement-on-2xx ensures no payment is charged for a 503, " +
                "so the same signed authorization stays valid for the retry.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Spring's default 405 envelope is empty, so an agent that calls
     * a paid endpoint with the wrong verb (commonly POST against a
     * GET-only endpoint, since most x402 examples on the public web
     * are LLM-inference services that POST) gets no hint about what
     * to do next. Replace it with a small JSON body that names the
     * supported verb, repeats the path, and points at the discovery
     * doc so a self-correcting client can recover without a doc dive.
     *
     * <p>Returns the {@code Allow} header per HTTP/1.1 §10.4.6 and
     * disables intermediate caching with {@code Cache-Control: no-store}
     * — a cached 405 served to a different client could mask a real
     * future intent change.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        String[] supported = ex.getSupportedMethods();
        String allowHeader = (supported == null || supported.length == 0)
                ? "GET"
                : String.join(", ", supported);
        // Tool-using agents that pass the 405 envelope through a
        // multi-step chain may lose the request's `Host` context
        // before they construct the follow-up call. Emit absolute
        // URLs in both `hint` and `discovery` so any downstream
        // consumer can act on the body without reconstructing the
        // origin from `Host` / `X-Forwarded-Host` headers.
        String origin = absoluteOrigin(request);
        String hint = (supported == null || supported.length == 0)
                ? "This path does not accept the method you used."
                : "Use " + supported[0] + " " + origin + request.getRequestURI()
                  + ". See " + origin + "/.well-known/x402 for the full agent flow.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "method_not_allowed");
        body.put("method", request.getMethod());
        body.put("supported", supported == null ? List.of() : List.of(supported));
        body.put("hint", hint);
        body.put("agent_action_hint", "Re-issue the same request with method " +
                ((supported == null || supported.length == 0) ? "GET" : supported[0]) +
                ". This service exposes only read endpoints; POST is never required. " +
                "GET /v1/pricing has the canonical free-then-paid call sequence with verbs.");
        body.put("discovery", origin + "/.well-known/x402");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, allowHeader)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Reconstructs the public origin (scheme + host + optional port)
     * from forwarded headers — Tomcat's {@code remoteIp} valve has
     * already trusted {@code X-Forwarded-Proto} / {@code X-Forwarded-Host}
     * from the cloudflared bridge per {@code application.yml}, so
     * {@code request.getScheme()} / {@code getServerName()} return
     * the public values, not the loopback the container sees.
     */
    private static String absoluteOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return defaultPort
                ? scheme + "://" + host
                : scheme + "://" + host + ":" + port;
    }

    /**
     * Generic Postgres / JDBC failure that didn't get caught closer
     * to the call site. Maps to 503 with no retry hint — the agent
     * should treat the request as "service degraded" rather than
     * "request malformed" (400) or "internal bug" (500).
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex) {
        log.error("Database unreachable: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "service_unavailable");
        body.put("message", "Persistent storage is temporarily unreachable. Retry shortly.");
        body.put("agent_action_hint", "Wait at least Retry-After seconds (10) before retrying. " +
                "Settlement-on-2xx ensures no payment is charged for a 503; the same signed " +
                "authorization remains valid for the retry.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
