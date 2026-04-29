package com.dartintel.api.api;

import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.payment.X402Properties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the {@code /.well-known/x402} discovery document so that
 * indexers like <a href="https://www.x402scan.com">x402scan</a> can
 * register the service automatically without probing each endpoint.
 *
 * <p>The response includes:
 *
 * <ul>
 *   <li><b>service</b> — human-readable identity (name, description,
 *       homepage, repository, openapi pointer).</li>
 *   <li><b>x402</b> — the merchant's wallet, CAIP-2 network, and the
 *       USDC asset address that every paid endpoint settles against.</li>
 *   <li><b>resources</b> — one object per paid endpoint, generated
 *       from {@link X402Paywall} annotations. Each carries the URL
 *       template, HTTP method, USDC price, pricing mode (FIXED or
 *       PER_RESULT), and the multiplier query parameter when the price
 *       is dynamic. This is the metadata indexers use to decide whether
 *       to surface the listing in search results.</li>
 * </ul>
 *
 * <p>The legacy v1 {@code resources} array (just an array of URLs) is
 * also included alongside the v2 objects, so older crawlers that
 * iterate the field by index still resolve a real endpoint.
 */
@RestController
@Tag(name = "Discovery", description = "Well-known discovery documents for x402 indexers.")
public class WellKnownController {

    private static final String PUBLIC_BASE_URL = "https://api.koreafilings.com";
    private static final String SERVICE_NAME = "Korea Filings";
    private static final String SERVICE_DESCRIPTION =
            "Search Korean DART (전자공시) corporate disclosures by name " +
            "and pay per call in USDC via x402 on Base. Free company " +
            "directory + recent feed for discovery, paid AI-summarised " +
            "English filings for content.";
    private static final String SERVICE_HOMEPAGE = "https://koreafilings.com";
    private static final String SERVICE_REPOSITORY =
            "https://github.com/OldTemple91/korea-filings-api";
    private static final String SERVICE_OPENAPI =
            PUBLIC_BASE_URL + "/openapi.json";

    private final X402Properties x402Properties;
    private final RequestMappingHandlerMapping handlerMapping;

    public WellKnownController(
            X402Properties x402Properties,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.x402Properties = x402Properties;
        this.handlerMapping = handlerMapping;
    }

    @GetMapping(value = "/.well-known/x402", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements // free, unauthenticated
    @Operation(
            summary = "x402 service discovery document",
            description = """
                    Lists every paid endpoint this service exposes, plus the
                    merchant wallet and the USDC asset address. Used by
                    x402 ecosystem indexers (x402scan, etc.) to register
                    the service without probing each endpoint.
                    """
    )
    public Map<String, Object> discoveryDocument() {
        List<Map<String, Object>> resourceObjects = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().hasMethodAnnotation(X402Paywall.class))
                .sorted(Comparator.comparing(e -> pathPattern(e.getKey())))
                .map(this::toResourceObject)
                .toList();

        // The discovery spec validated by the @agentcash/discovery CLI
        // (which is what x402scan runs to index a server) requires:
        //   - version: number (NOT string)
        //   - resources: array<string> (URL strings, NOT objects)
        //   - description: string (optional, surfaced in directory)
        //   - instructions: string (optional, surfaced to agents)
        // Any deviation from those types makes the strict-mode validator
        // silently mark the document as missing. Keep those fields
        // exactly to spec; add richer metadata under sibling keys that
        // the validator's `passthrough` behaviour will preserve for
        // clients that look for them.
        List<String> resources = resourceObjects.stream()
                .map(r -> (String) r.get("url"))
                .toList();

        Map<String, Object> service = new LinkedHashMap<>();
        service.put("name", SERVICE_NAME);
        service.put("description", SERVICE_DESCRIPTION);
        service.put("homepage", SERVICE_HOMEPAGE);
        service.put("repository", SERVICE_REPOSITORY);
        service.put("openapi", SERVICE_OPENAPI);

        Map<String, Object> x402 = new LinkedHashMap<>();
        x402.put("scheme", "exact");
        x402.put("network", x402Properties.network());
        x402.put("asset", x402Properties.asset());
        x402.put("recipient", x402Properties.recipientAddress());

        Map<String, Object> body = new LinkedHashMap<>();
        // === Spec-required fields (strict types) ===
        body.put("version", 1);
        body.put("resources", resources);
        body.put("description", SERVICE_DESCRIPTION);
        body.put("instructions",
                "Free company directory + recent feed at /v1/companies and " +
                "/v1/disclosures/recent for cold-start discovery; paid " +
                "summaries at /v1/disclosures/by-ticker/{ticker}?limit=N " +
                "(0.005 × N USDC, dynamic price in 402) and " +
                "/v1/disclosures/{rcptNo}/summary (0.005 USDC fixed).");

        // === Optional sibling extensions (validator-tolerated) ===
        body.put("service", service);
        body.put("x402", x402);
        body.put("resourceDetails", resourceObjects);
        return body;
    }

    private Map<String, Object> toResourceObject(
            Map.Entry<RequestMappingInfo, HandlerMethod> entry
    ) {
        HandlerMethod handler = entry.getValue();
        X402Paywall annotation = handler.getMethodAnnotation(X402Paywall.class);
        RequestMappingInfo info = entry.getKey();

        String path = pathPattern(info);
        String method = info.getMethodsCondition().getMethods().isEmpty()
                ? "GET"
                : info.getMethodsCondition().getMethods().iterator().next().name();

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("url", PUBLIC_BASE_URL + path);
        resource.put("method", method);
        resource.put("description", annotation.description());
        resource.put("priceUsdc", annotation.priceUsdc());
        resource.put("priceMode", annotation.pricingMode().name().toLowerCase());

        if (annotation.pricingMode() == X402Paywall.Mode.PER_RESULT) {
            resource.put("countParam", annotation.countQueryParam());
            resource.put("defaultCount", annotation.defaultCount());
            resource.put("maxCount", annotation.maxCount());
        }

        return resource;
    }

    private static String pathPattern(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString();
        }
        return info.getPatternValues().iterator().next();
    }
}
