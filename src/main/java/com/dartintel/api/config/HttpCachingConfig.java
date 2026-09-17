package com.dartintel.api.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Conditional GET for the free feed (round-22).
 *
 * <p>{@code /v1/disclosures/recent} is polled every 30–60 s by
 * unattended scripts, and between DART filings the answer is
 * byte-for-byte identical. {@link ShallowEtagHeaderFilter} hashes the
 * rendered body into an {@code ETag}; a client that sends it back in
 * {@code If-None-Match} gets {@code 304 Not Modified} with an empty
 * body instead of the ~45 KB page. Most HTTP caching layers (browser
 * fetch, requests-cache, axios-cache, Cloudflare revalidation) do this
 * without any client code change.
 *
 * <p>Scope is deliberately the free feed only. The paid endpoints'
 * 402 challenges are per-request (error string and effective amount
 * depend on the query) and {@code /companies} is a search whose
 * results vary per query string — neither benefits from a body hash.
 *
 * <p>Ordering: the filter runs <em>inside</em> {@code RequestAuditFilter}
 * so the audit row records the 304 the client actually received, not
 * the 200 the controller produced.
 */
@Configuration
public class HttpCachingConfig {

    /** Audit filter order — one step outside the ETag filter. */
    public static final int AUDIT_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> recentFeedEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        reg.addUrlPatterns("/v1/disclosures/recent");
        reg.setOrder(Ordered.LOWEST_PRECEDENCE);
        reg.setName("recentFeedEtagFilter");
        return reg;
    }
}
