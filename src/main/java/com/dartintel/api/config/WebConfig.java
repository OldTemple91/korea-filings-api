package com.dartintel.api.config;

import com.dartintel.api.payment.X402PaywallInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final X402PaywallInterceptor x402PaywallInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The interceptor itself short-circuits for handler methods without
        // @X402Paywall, so a broad path pattern is safe and means new paid
        // endpoints under /v1 need no changes here.
        registry.addInterceptor(x402PaywallInterceptor).addPathPatterns("/v1/**");
    }

    /**
     * The landing page at koreafilings.com (and any agent / dashboard elsewhere)
     * needs to fetch /v1/pricing and /actuator/health from a different origin.
     * This is safe because:
     *   - all exposed endpoints are idempotent reads of public information,
     *   - paid endpoints still require a signed payment header (PAYMENT-SIGNATURE
     *     in v2, X-PAYMENT in v1 compat) that the browser cannot forge, and no
     *     third-party JS should ever hold the payer's key,
     *   - we do NOT allow credentials, so the wildcard origin pattern is sound.
     * Exposing PAYMENT-RESPONSE / X-PAYMENT-RESPONSE lets a browser-based x402
     * client read the settlement proof that {@code X402SettlementAdvice}
     * attaches on 2xx, and PAYMENT-REQUIRED carries the v2 challenge.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(
                        "PAYMENT-REQUIRED",
                        "PAYMENT-RESPONSE",
                        "X-PAYMENT-RESPONSE")
                .allowCredentials(false)
                .maxAge(3600);
        registry.addMapping("/actuator/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET")
                .allowCredentials(false)
                .maxAge(3600);
        registry.addMapping("/.well-known/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
