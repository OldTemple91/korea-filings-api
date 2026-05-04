package com.dartintel.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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
                    return;
                }
                log.info("{} {}", LOG_TAG, formatLine(request, status));
            } catch (RuntimeException e) {
                log.debug("audit logging skipped: {}", e.getMessage());
            }
        }
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
