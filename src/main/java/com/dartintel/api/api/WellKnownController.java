package com.dartintel.api.api;

import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.payment.X402Properties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
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
    /**
     * Pre-built well-known document cached at boot. Same rationale as
     * {@link PublicController#cachedPricing}: free endpoint that
     * indexers re-crawl, contents are configuration-time constants.
     */
    private volatile Map<String, Object> cachedDocument;

    public WellKnownController(
            X402Properties x402Properties,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.x402Properties = x402Properties;
        this.handlerMapping = handlerMapping;
    }

    /**
     * Pre-built AWP (Agent Web Protocol) manifest. Same surface as
     * {@code /.well-known/x402} but in the {@code awp_version / domain
     * / intent / actions[]} shape that AWP indexers (Open402 directory
     * crawler, agentwebprotocol.org) probe for. Free endpoints are
     * listed alongside paid ones so a cold-start agent can plan the
     * full free → paid call sequence from one document.
     */
    private volatile Map<String, Object> cachedAgentJson;

    @PostConstruct
    void buildDiscoveryCache() {
        this.cachedDocument = buildDiscoveryDocument();
        this.cachedAgentJson = buildAgentJsonDocument();
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
        return cachedDocument;
    }

    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements // free, unauthenticated
    @Operation(
            summary = "Agent Web Protocol (AWP) manifest",
            description = """
                    AWP-shaped sibling to {@code /.well-known/x402}. Lists
                    every public action (free + paid) with method, endpoint,
                    parameters, and a per-action pointer to the x402
                    payment descriptor for paid actions. Targeted at the
                    Open402 directory crawler and other AWP-aware indexers
                    that probe this path.
                    """
    )
    public Map<String, Object> agentJsonDocument() {
        return cachedAgentJson;
    }

    private Map<String, Object> buildDiscoveryDocument() {
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
        // `extra` mirrors the EIP-712 domain values the 402 challenge
        // will carry in `accepts[].extra`. SDK 0.1.1+ pins the
        // canonical (name, version, asset) triple per chain in a
        // KNOWN_DOMAINS allowlist and refuses to sign if the actual
        // 402 challenge advertises anything else. Surfacing the same
        // values here lets a cold-start agent verify "the server I
        // discovered will work with my pinned SDK" before issuing the
        // first paid call. The values come straight from the live
        // X402Properties so a config drift in the running app is
        // immediately visible to indexers.
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("name", x402Properties.tokenName());
        extra.put("version", x402Properties.tokenVersion());
        x402.put("extra", extra);

        Map<String, Object> body = new LinkedHashMap<>();
        // === Spec-required fields (strict types) ===
        body.put("version", 1);
        body.put("resources", resources);
        body.put("description", SERVICE_DESCRIPTION);
        body.put("instructions",
                "Cold-start agent flow (free → paid, no API key, no signup): " +
                "(1) GET /v1/companies?q={koreanOrEnglishName} resolves a " +
                "Korean company name to a six-digit KRX ticker (free, fuzzy). " +
                "(2) GET /v1/disclosures/by-ticker?ticker={ticker}&limit={n} " +
                "returns up to n AI-summarised English filings, newest first " +
                "(paid, 0.005 USDC × n on Base). " +
                "(3) Optional re-fetch of a single summary by 14-digit DART " +
                "receipt number: GET /v1/disclosures/summary?rcptNo={rcptNo} " +
                "(paid, 0.005 USDC fixed). " +
                "Send the signed payment in the PAYMENT-SIGNATURE header " +
                "(v2 transport spec). The legacy X-PAYMENT header is also " +
                "accepted for older 0.2.x SDK / MCP releases. " +
                "GET /v1/pricing returns the canonical machine-readable " +
                "descriptor including required params and example calls.");

        // === Optional sibling extensions (validator-tolerated) ===
        body.put("service", service);
        body.put("x402", x402);
        body.put("resourceDetails", resourceObjects);
        return body;
    }

    /**
     * Builds the AWP {@code agent.json} document from the same paid-
     * endpoint metadata that drives {@code /.well-known/x402}, plus a
     * hardcoded list of free actions (free endpoints are not
     * annotated for x402 since there's no price to declare).
     *
     * <p>Schema follows agentwebprotocol.org v0.2: {@code awp_version},
     * {@code domain}, {@code intent}, {@code actions[]} with
     * {@code id} / {@code method} / {@code endpoint} / {@code intent}
     * / {@code parameters} / optional {@code payment} pointer.
     */
    private Map<String, Object> buildAgentJsonDocument() {
        List<Map<String, Object>> freeActions = List.of(
                action(
                        "find-company",
                        "GET",
                        "/v1/companies",
                        "Resolve a Korean company name (Korean or English) to a six-digit KRX ticker via trigram fuzzy search.",
                        Map.of(
                                "q", param("query", "string", true,
                                        "Company name in Korean or English. Returns up to a few fuzzy matches with isExactMatch flag.")
                        ),
                        null
                ),
                action(
                        "list-recent-filings",
                        "GET",
                        "/v1/disclosures/recent",
                        "Market-wide metadata feed of the most recent DART filings. Use to discover rcptNo values without paying.",
                        Map.of(
                                "limit", param("query", "integer", false,
                                        "Max filings to return (1-100, default 20)."),
                                "since_hours", param("query", "integer", false,
                                        "Look back this many hours (1-168, default 24).")
                        ),
                        null
                )
        );

        List<Map<String, Object>> paidActions = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().hasMethodAnnotation(X402Paywall.class))
                .sorted(Comparator.comparing(e -> pathPattern(e.getKey())))
                .map(this::toAgentJsonAction)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("awp_version", "0.2");
        body.put("domain", "api.koreafilings.com");
        body.put("intent",
                "Pay-per-call API for AI-summarised Korean DART corporate disclosures. " +
                "Free company directory + recent feed for discovery; paid summaries " +
                "for content via x402 (USDC on Base mainnet).");
        // Free actions first so a cold-start agent reads the cheap
        // discovery path before the paid ones — same ordering as the
        // /v1/pricing workflow steps.
        body.put("actions", concat(freeActions, paidActions));
        // Top-level payment descriptor pointer. Per-action `payment`
        // blocks point at this same doc; this duplicate field saves a
        // round-trip for indexers that summarise the manifest at
        // top level.
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("scheme", "x402");
        payment.put("discovery", "/.well-known/x402");
        body.put("payment", payment);
        return body;
    }

    private Map<String, Object> toAgentJsonAction(
            Map.Entry<RequestMappingInfo, HandlerMethod> entry
    ) {
        HandlerMethod handler = entry.getValue();
        X402Paywall annotation = handler.getMethodAnnotation(X402Paywall.class);
        RequestMappingInfo info = entry.getKey();

        String path = pathPattern(info);
        String method = info.getMethodsCondition().getMethods().isEmpty()
                ? "GET"
                : info.getMethodsCondition().getMethods().iterator().next().name();

        // Param descriptions mirror what the bazaar extension under the
        // 402 `accepts[].extensions.bazaar.info.input` block declares.
        // Hardcoded here because the @X402Paywall annotation only knows
        // *which* params are required, not their meaning.
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        if ("/v1/disclosures/by-ticker".equals(path)) {
            params.put("ticker", param("query", "string", true,
                    "Six-digit KRX ticker (e.g. 005930 for Samsung Electronics). " +
                    "Resolve from a company name via the free /v1/companies?q={name} endpoint."));
            params.put("limit", param("query", "integer", false,
                    "Max filings to return (1-50, default 5). Final price is " +
                    "0.005 × limit USDC. The agent is charged for limit, not " +
                    "for the actual row count returned."));
        } else if ("/v1/disclosures/summary".equals(path)) {
            params.put("rcptNo", param("query", "string", true,
                    "14-digit DART receipt number, e.g. 20260424900874. Obtain " +
                    "from /v1/disclosures/recent or /v1/disclosures/by-ticker " +
                    "response — never LLM-knowable."));
        }

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("scheme", "x402");
        payment.put("priceUsdc", annotation.priceUsdc());
        payment.put("priceMode", annotation.pricingMode().name().toLowerCase());
        payment.put("discovery", "/.well-known/x402");

        // Action id derived from the trailing path segments — keeps
        // ids stable across endpoint additions and matches Python SDK
        // method names (get_recent_filings, get_summary, ...).
        String id = idFromPath(path);
        return action(id, method, path, annotation.description(), params, payment);
    }

    private static String idFromPath(String path) {
        // /v1/disclosures/by-ticker -> get-by-ticker
        // /v1/disclosures/summary   -> get-summary
        String[] segments = path.split("/");
        String last = segments[segments.length - 1];
        return "get-" + last;
    }

    private static Map<String, Object> action(
            String id, String method, String endpoint,
            String intent, Map<String, ?> parameters, Map<String, Object> payment
    ) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("method", method);
        a.put("endpoint", endpoint);
        a.put("intent", intent);
        if (parameters != null && !parameters.isEmpty()) {
            a.put("parameters", parameters);
        }
        if (payment != null) {
            a.put("payment", payment);
        }
        return a;
    }

    private static Map<String, Object> param(String in, String type, boolean required, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("in", in);
        p.put("type", type);
        p.put("required", required);
        p.put("description", description);
        return p;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new java.util.ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
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
