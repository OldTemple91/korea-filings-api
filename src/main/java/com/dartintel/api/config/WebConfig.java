package com.dartintel.api.config;

import com.dartintel.api.payment.X402PaywallInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
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
}
