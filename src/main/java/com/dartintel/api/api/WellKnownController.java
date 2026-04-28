package com.dartintel.api.api;

import com.dartintel.api.payment.X402Paywall;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Implements the {@code /.well-known/x402} discovery document so that
 * indexers like <a href="https://www.x402scan.com">x402scan</a> can
 * register the service automatically without probing each endpoint.
 *
 * <p>The format is the minimal payload the x402scan discovery spec
 * recognises:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "resources": [ "https://host/path", ... ]
 * }
 * }</pre>
 *
 * <p>The list is generated dynamically from every handler annotated with
 * {@link X402Paywall}, so adding a new paid endpoint exposes it here
 * with no extra wiring. URLs are emitted both in their templated form
 * (e.g. {@code .../{rcptNo}/summary}) and in a concrete known-good
 * sample form so indexers that probe directly always land on a real
 * 402 response.
 */
@RestController
@Tag(name = "Discovery", description = "Well-known discovery documents for x402 indexers.")
public class WellKnownController {

    /**
     * Hard-coded sample so x402scan's fallback probe always hits a
     * disclosure that exists in the Postgres cache. We intentionally
     * pin a known rcpt_no rather than picking the latest one — a
     * disclosure that exists today will still exist tomorrow, and
     * indexers do not need a moving target.
     */
    private static final String SAMPLE_RCPT_NO = "20260427901120";

    private final RequestMappingHandlerMapping handlerMapping;
    private final String publicBaseUrl;

    public WellKnownController(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.handlerMapping = handlerMapping;
        // Hard-coded for now; once we have a config for it we can plumb
        // X402Properties or a dedicated app.publicBaseUrl. The forward-
        // headers strategy ensures other endpoints already self-report
        // https://, but this document is generated at request time and
        // we want the same canonical URL no matter the request scheme.
        this.publicBaseUrl = "https://api.koreafilings.com";
    }

    @GetMapping(value = "/.well-known/x402", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements // free, unauthenticated
    @Operation(
            summary = "x402 service discovery document",
            description = """
                    Lists every paid endpoint this service exposes in the format
                    expected by x402 ecosystem indexers (x402scan, etc.). Each
                    paid endpoint appears twice — once as a path template and
                    once as a concrete known-good sample URL — so an indexer
                    can verify the runtime 402 response without guessing path
                    parameters.
                    """
    )
    public Map<String, Object> discoveryDocument() {
        List<String> resources = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().hasMethodAnnotation(X402Paywall.class))
                .sorted(Comparator.comparing(e -> pathPattern(e.getKey())))
                .flatMap(e -> {
                    String template = pathPattern(e.getKey());
                    return List.of(
                            publicBaseUrl + template,
                            publicBaseUrl + template.replace("{rcptNo}", SAMPLE_RCPT_NO)
                    ).stream();
                })
                .distinct()
                .toList();
        return Map.of(
                "version", 1,
                "resources", resources
        );
    }

    private static String pathPattern(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString();
        }
        return info.getPatternValues().iterator().next();
    }

    @SuppressWarnings("unused")
    private static String firstMethod(HandlerMethod handler) {
        // Reserved for future extensions if x402scan begins requiring
        // method metadata in the resources array.
        return "GET";
    }
}
