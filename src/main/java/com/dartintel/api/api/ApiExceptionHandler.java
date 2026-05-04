package com.dartintel.api.api;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
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
