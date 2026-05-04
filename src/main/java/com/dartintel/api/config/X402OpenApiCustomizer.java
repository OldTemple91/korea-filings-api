package com.dartintel.api.config;

import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.payment.X402Properties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Injects the {@code x-payment-info} OpenAPI extension on every paid
 * operation at runtime, using the live {@link X402Properties} so the
 * declared network / asset / recipient / EIP-712 token domain match
 * whatever environment the JVM booted under (Base Sepolia vs Base
 * mainnet vs a future chain).
 *
 * <p>The previous implementation declared the extension via the
 * {@code @Extension} annotation on each controller method, but Java
 * annotations only accept compile-time constants — meaning the values
 * were a hardcoded mainnet snapshot. An agent that statically
 * pre-fetches {@code /openapi.json} on a Sepolia deployment would see
 * mainnet wallet / asset addresses and build a payment that the
 * facilitator would reject. This customizer reads
 * {@link X402Properties} from the live application context, so the
 * declared values always match what the 402 challenge will quote.
 *
 * <p>x402scan and other discovery tools key paid classification on
 * the presence of {@code x-payment-info}, so dropping the static
 * annotation while not adding a runtime equivalent would silently
 * downgrade every paid endpoint to {@code apiKey-only}. This bean is
 * what keeps that classification correct.
 */
@Configuration
public class X402OpenApiCustomizer {

    private static final BigDecimal USDC_ATOMIC = new BigDecimal(1_000_000);

    private final X402Properties x402Properties;
    private final RequestMappingHandlerMapping handlerMapping;

    public X402OpenApiCustomizer(
            X402Properties x402Properties,
            // Spring Boot also registers a "controllerEndpointHandlerMapping"
            // for the actuator; we only want the MVC-controller one here so
            // /actuator/* endpoints don't get scanned for @X402Paywall.
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.x402Properties = x402Properties;
        this.handlerMapping = handlerMapping;
    }

    @org.springframework.context.annotation.Bean
    public OpenApiCustomizer x402PaymentInfoCustomizer() {
        return openApi -> {
            Map<String, PaywallMeta> paidByPath = collectPaidEndpoints();
            if (paidByPath.isEmpty() || openApi.getPaths() == null) {
                return;
            }
            for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
                PaywallMeta meta = paidByPath.get(entry.getKey());
                if (meta == null) {
                    continue;
                }
                PathItem pathItem = entry.getValue();
                Operation op = methodOperation(pathItem, meta.method);
                if (op == null) {
                    continue;
                }
                Map<String, Object> extensionPayload = buildExtensionPayload(meta);
                if (op.getExtensions() == null) {
                    op.setExtensions(new LinkedHashMap<>());
                }
                op.getExtensions().put("x-payment-info", extensionPayload);
            }
        };
    }

    /**
     * Walk every Spring MVC handler, pick out the ones annotated with
     * {@link X402Paywall}, and key them by URL path so the customizer
     * can match them against OpenAPI's path map. Returns a map of
     * {@code path → paywall metadata + http method} so per-result
     * pricing can carry the count parameter through.
     */
    private Map<String, PaywallMeta> collectPaidEndpoints() {
        Map<String, PaywallMeta> result = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            X402Paywall annotation = handler.getMethodAnnotation(X402Paywall.class);
            if (annotation == null) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            String path = pathPattern(info);
            HttpMethod httpMethod = firstHttpMethod(info);
            if (path == null || httpMethod == null) {
                continue;
            }
            result.put(path, new PaywallMeta(annotation, httpMethod));
        }
        return result;
    }

    private static Operation methodOperation(PathItem pathItem, HttpMethod method) {
        return switch (method) {
            case GET -> pathItem.getGet();
            case POST -> pathItem.getPost();
            case PUT -> pathItem.getPut();
            case PATCH -> pathItem.getPatch();
            case DELETE -> pathItem.getDelete();
            case HEAD -> pathItem.getHead();
            case OPTIONS -> pathItem.getOptions();
            case TRACE -> pathItem.getTrace();
        };
    }

    /**
     * Build the {@code x-payment-info} object that x402scan and
     * similar discovery tools consume to classify a route as
     * {@code paid}. Uses live {@link X402Properties} for network,
     * asset, recipient, and EIP-712 token domain so the declaration
     * tracks whichever environment the JVM booted under.
     */
    private Map<String, Object> buildExtensionPayload(PaywallMeta meta) {
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("scheme", "exact");
        extension.put("network", x402Properties.network());
        extension.put("asset", x402Properties.asset());
        extension.put("payTo", x402Properties.recipientAddress());
        extension.put("amount", toAtomicAmount(meta.annotation.priceUsdc()));
        extension.put("amountMode",
                meta.annotation.pricingMode() == X402Paywall.Mode.PER_RESULT
                        ? "perResult" : "fixed");
        if (meta.annotation.pricingMode() == X402Paywall.Mode.PER_RESULT) {
            extension.put("countParam", meta.annotation.countQueryParam());
            extension.put("defaultCount", meta.annotation.defaultCount());
            extension.put("maxCount", meta.annotation.maxCount());
        }
        extension.put("tokenName", x402Properties.tokenName());
        extension.put("tokenVersion", x402Properties.tokenVersion());
        return extension;
    }

    /**
     * Convert a human-readable USDC price (e.g. {@code "0.005"}) to
     * the atomic representation x402 uses on the wire (USDC has six
     * decimals, so "0.005" → "5000").
     */
    private static String toAtomicAmount(String priceUsdc) {
        return new BigDecimal(priceUsdc)
                .multiply(USDC_ATOMIC)
                .setScale(0, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String pathPattern(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null
                && !info.getPathPatternsCondition().getPatterns().isEmpty()) {
            return info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString();
        }
        if (info.getPatternValues() == null || info.getPatternValues().isEmpty()) {
            return null;
        }
        return info.getPatternValues().iterator().next();
    }

    private static HttpMethod firstHttpMethod(RequestMappingInfo info) {
        Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return HttpMethod.GET;
        }
        return switch (methods.iterator().next()) {
            case GET -> HttpMethod.GET;
            case POST -> HttpMethod.POST;
            case PUT -> HttpMethod.PUT;
            case PATCH -> HttpMethod.PATCH;
            case DELETE -> HttpMethod.DELETE;
            case HEAD -> HttpMethod.HEAD;
            case OPTIONS -> HttpMethod.OPTIONS;
            case TRACE -> HttpMethod.TRACE;
        };
    }

    private record PaywallMeta(X402Paywall annotation, HttpMethod method) {
    }
}
