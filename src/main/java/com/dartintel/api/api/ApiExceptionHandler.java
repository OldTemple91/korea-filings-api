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
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation_failed");
        body.put("message", ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; ")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation_failed");
        body.put("message", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; ")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "missing_parameter");
        body.put("message", "required parameter '" + ex.getParameterName() + "' is missing");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
        String hint = (supported == null || supported.length == 0)
                ? "This path does not accept the method you used."
                : "Use " + supported[0] + " " + request.getRequestURI()
                  + ". See /.well-known/x402 for the full agent flow.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "method_not_allowed");
        body.put("method", request.getMethod());
        body.put("supported", supported == null ? List.of() : List.of(supported));
        body.put("hint", hint);
        body.put("discovery", "/.well-known/x402");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, allowHeader)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
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
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
