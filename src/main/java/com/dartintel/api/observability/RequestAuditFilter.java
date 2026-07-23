package com.dartintel.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * One structured log line per non-GET request OR any 4xx/5xx response.
 *
 * <p>Used to quantify how often agents call our paid GET endpoints
 * with POST and whether they're attaching payment headers, so we can
 * decide whether opening parallel POST handlers would capture real
 * revenue or just expand the public surface for a low-signal probe
 * pattern.
 *
 * <p>Captures structural fields only:
 * <ul>
 *   <li>{@code method}, {@code path}, {@code status}</li>
 *   <li>{@code ip} from {@code CF-Connecting-IP} (Cloudflare-injected
 *       real client IP), falling back to {@code request.getRemoteAddr()}
 *       which already incorporates Tomcat's {@code X-Forwarded-For}
 *       handling per {@code application.yml}</li>
 *   <li>{@code ua} from {@code User-Agent}</li>
 *   <li>{@code query} — sorted list of query parameter <em>key
 *       names</em>, never their values; tells us whether a POST caller
 *       knows the parameter shape (e.g. {@code [rcptNo]}) or is going
 *       blind</li>
 *   <li>{@code body} — content length from the {@code Content-Length}
 *       header, no body read</li>
 *   <li>{@code ct} — content type</li>
 *   <li>{@code xpay}, {@code pay_sig} — boolean presence flags for
 *       {@code X-PAYMENT} and {@code PAYMENT-SIGNATURE}; never the
 *       header values, since those carry signed nonces and should not
 *       enter log archives</li>
 * </ul>
 *
 * <p>Disabled by default. Enable with {@code REQUEST_AUDIT_ENABLED=true}
 * in {@code .env} on the production VM. The bean is not registered when
 * disabled, so there is zero filter overhead in local dev and tests.
 *
 * <p>One {@code REQ_AUDIT} line per qualifying request, prefix-tagged
 * for trivial grep:
 * <pre>{@code grep REQ_AUDIT $LOG | jq ...}</pre>
 * (Output is plain key=value, not JSON, so no parser is needed; pipe
 * to {@code awk} for ad-hoc summaries.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "audit.requests.enabled", havingValue = "true")
public class RequestAuditFilter extends OncePerRequestFilter {

    static final String LOG_TAG = "REQ_AUDIT";

    /**
     * Free GET paths whose successful (2xx) responses we want to keep
     * in {@code request_audit} on top of the default "non-GET or
     * 4xx/5xx" rule.
     *
     * <p>Originally the audit filter skipped every GET 2xx to keep the
     * table small — sensible when "almost every successful GET is a
     * browser hitting the landing page". But that turned the table
     * blind to actual agent activity on our free surface: the 2026-05-28
     * funnel review showed 3,465 lifetime successful reads of
     * {@code /.well-known/x402} in Prometheus, against literal zero
     * GET 200 rows in {@code request_audit} — IPs / UAs / params for
     * who actually consumed the free API were lost. Round-15a opts in
     * the specific paths we care about (discovery docs, name search,
     * sample, pricing, recent feed). Volume is bounded: lifetime hit
     * counts above translate to roughly 100–200 audit rows per day
     * even at peak, which is comparable to the existing noise floor
     * and far below the 6k+ daily POST 405 from the broken axios bot
     * that the table already absorbs.
     *
     * <p>Explicit omissions:
     * <ul>
     *   <li>{@code /openapi.json}, {@code /v3/api-docs} — SDK
     *       generators and OpenAPI registry poll these dozens of times
     *       per day; the IP/UA distribution is dominated by automation,
     *       not consumer activity.</li>
     *   <li>{@code /swagger-ui/...} static assets — pure transport, no
     *       signal.</li>
     *   <li>{@code /favicon.ico}, {@code /actuator/*} — uninteresting
     *       infrastructure noise.</li>
     * </ul>
     */
    static final Set<String> AUDIT_GET_2XX_PATHS = Set.of(
            "/v1/companies",
            "/v1/disclosures/recent",
            "/v1/disclosures/sample",
            "/v1/pricing",
            "/.well-known/x402",
            "/.well-known/x402.json",
            "/.well-known/agent.json",
            "/llms.txt",
            "/llms-full.txt",
            "/.well-known/llms.txt",
            // Round-16: the two PAID endpoints. A successful paid call
            // returns GET 200, which the default rule skips — so before
            // round-16 a settled paid call was invisible in
            // request_audit and only surfaced (days later) via
            // payment_log. The 402 challenge was logged but not the
            // successful retry. Auditing the 200 here captures the
            // caller's IP / UA / timing for every completed paid call,
            // which is exactly the signal needed to tell a real
            // external customer apart from a catalog verifier (e.g. the
            // 2026-06-12 CoinbaseBazaarDiscovery settlement). Volume is
            // trivially low — paid calls are the rarest traffic we have.
            "/v1/disclosures/by-ticker",
            "/v1/disclosures/summary"
    );

    /**
     * Optional persister. Present only when {@code audit.requests.persist=true};
     * {@link ObjectProvider} resolves to empty when the bean isn't
     * registered, so the filter degrades gracefully to log-only mode.
     */
    private final ObjectProvider<RequestAuditPersister> persister;

    public RequestAuditFilter(ObjectProvider<RequestAuditPersister> persister) {
        this.persister = persister;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            // Audit must never escape the finally block. A logging
            // hiccup or a malformed header should not turn a real
            // 200 into a 500.
            try {
                int status = response.getStatus();
                String method = request.getMethod();
                if ("GET".equalsIgnoreCase(method) && status < 400) {
                    // Round-15a: keep GET 2xx for the explicitly-tracked
                    // free paths (see AUDIT_GET_2XX_PATHS). All other
                    // GET 2xx — landing page bounces, springdoc polls,
                    // static assets, /actuator/* — still skip.
                    if (!AUDIT_GET_2XX_PATHS.contains(request.getRequestURI())) {
                        return;
                    }
                }
                log.info("{} {}", LOG_TAG, formatLine(request, status));
                // Best-effort persistence to the request_audit table.
                // The persister is async + bounded, so this is a queue
                // offer, not a DB write — never blocks the request.
                RequestAuditPersister p = persister.getIfAvailable();
                if (p != null) {
                    p.enqueue(toRow(request, status));
                }
            } catch (RuntimeException e) {
                log.debug("audit logging skipped: {}", e.getMessage());
            }
        }
    }

    /**
     * Builds a {@link RequestAudit} row from the same source data the
     * log line uses. Kept package-private for symmetry with
     * {@link #formatLine(HttpServletRequest, int)}.
     */
    static RequestAudit toRow(HttpServletRequest request, int status) {
        return RequestAudit.builder()
                .ts(Instant.now())
                .method(truncate(request.getMethod(), 8))
                .path(truncate(request.getRequestURI(), 256))
                .status(status)
                .ip(truncate(rawClientIp(request), 64))
                .userAgent(truncate(sanitiseOrNull(request.getHeader("User-Agent")), 256))
                .queryKeys(truncate(rawSortedQueryKeys(request), 512))
                .bodyBytes(rawContentLength(request))
                .contentType(truncate(sanitiseOrNull(request.getContentType()), 128))
                .hasXPayment(request.getHeader("X-PAYMENT") != null)
                .hasPaymentSig(request.getHeader("PAYMENT-SIGNATURE") != null)
                .build();
    }

    /** Like {@link #clientIp} but without the {@code "-"} placeholder for logs. */
    private static String rawClientIp(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return sanitiseOrNull(cf);
        }
        String addr = req.getRemoteAddr();
        return sanitiseOrNull(addr);
    }

    /**
     * Same CR/LF stripping + length cap as {@link #sanitise(String)}
     * but returns {@code null} on missing input. The log path uses
     * {@code "-"} for grep convenience; the DB row uses {@code NULL}
     * because {@code WHERE col IS NULL} reads cleaner than
     * {@code WHERE col = '-'} and prevents accidental aggregation
     * over the placeholder.
     */
    static String sanitiseOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String stripped = value.replaceAll("[\r\n\t]", " ");
        if (stripped.length() > 200) {
            stripped = stripped.substring(0, 200) + "...";
        }
        return stripped;
    }

    private static Long rawContentLength(HttpServletRequest req) {
        long len = req.getContentLengthLong();
        return len < 0 ? null : len;
    }

    private static String rawSortedQueryKeys(HttpServletRequest req) {
        if (req.getParameterMap().isEmpty()) {
            return null;
        }
        // Comma-separated rather than the bracketed log format
        // (the DB column already implies "this is a list").
        return new TreeSet<>(req.getParameterMap().keySet())
                .stream()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    /**
     * VARCHAR(N) safety net — Postgres rejects oversize inserts at the
     * boundary. Trim with an ellipsis marker rather than letting
     * Hibernate truncate silently or the INSERT fail.
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * Package-private for unit tests. Callers must guarantee non-null
     * request; status is the resolved HTTP status code.
     */
    static String formatLine(HttpServletRequest request, int status) {
        return String.format(
                "method=%s path=%s status=%d ip=%s ua=%s query=%s body=%s ct=%s xpay=%s pay_sig=%s",
                sanitiseShort(request.getMethod()),
                sanitiseShort(request.getRequestURI()),
                status,
                clientIp(request),
                quote(sanitise(request.getHeader("User-Agent"))),
                sortedQueryKeys(request),
                contentLength(request),
                quote(sanitise(request.getContentType())),
                request.getHeader("X-PAYMENT") != null,
                request.getHeader("PAYMENT-SIGNATURE") != null);
    }

    private static String clientIp(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return sanitiseShort(cf);
        }
        // Tomcat's remoteIp filter (configured in application.yml)
        // already replaces getRemoteAddr() with the X-Forwarded-For
        // first hop when the immediate peer is in the trusted-proxy
        // CIDR range, so this fallback is correct in our deployment.
        String addr = req.getRemoteAddr();
        return addr == null ? "-" : sanitiseShort(addr);
    }

    private static String contentLength(HttpServletRequest req) {
        long len = req.getContentLengthLong();
        return len < 0 ? "-" : Long.toString(len);
    }

    private static String sortedQueryKeys(HttpServletRequest req) {
        if (req.getParameterMap().isEmpty()) {
            return "[]";
        }
        // TreeSet for deterministic ordering — makes log lines diffable
        // across requests with the same shape.
        TreeSet<String> keys = new TreeSet<>(req.getParameterMap().keySet());
        return keys.toString();
    }

    /**
     * Strips CR/LF/TAB and caps at 200 chars. Defangs log-injection
     * attempts via crafted User-Agent / Content-Type / IP headers
     * that try to inject a fake follow-up log line. The
     * {@link com.dartintel.api.payment.X402PaywallInterceptor} uses
     * the same approach for the same reason.
     */
    static String sanitise(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        String stripped = value.replaceAll("[\r\n\t]", " ");
        if (stripped.length() > 200) {
            stripped = stripped.substring(0, 200) + "...";
        }
        return stripped;
    }

    /**
     * Like {@link #sanitise(String)} but caps shorter for fields
     * (method, path, IP) that should never legitimately be long.
     */
    static String sanitiseShort(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        String stripped = value.replaceAll("[\r\n\t]", " ");
        if (stripped.length() > 80) {
            stripped = stripped.substring(0, 80) + "...";
        }
        return stripped;
    }

    /**
     * Wraps a value in double-quotes when it contains whitespace or
     * an equals sign so the resulting log line stays parseable as
     * space-separated key=value pairs. Backslash-escapes any internal
     * quotes for the same reason.
     */
    static String quote(String value) {
        if (value == null || value.equals("-")) {
            return "-";
        }
        if (value.indexOf(' ') < 0 && value.indexOf('=') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
