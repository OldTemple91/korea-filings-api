package com.dartintel.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-22: conditional GET on the free feed. The ETag filter must be
 * scoped to the free, polled surface only — never the paid paths,
 * whose 402 challenges are per-request — and must sit inside the
 * audit filter so the audit row records the 304 the client saw.
 */
class HttpCachingConfigTest {

    private final HttpCachingConfig config = new HttpCachingConfig();

    @Test
    void etagFilterCoversTheFreeFeedOnly() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg = config.recentFeedEtagFilter();

        assertThat(reg.getUrlPatterns()).containsExactly("/v1/disclosures/recent");
    }

    @Test
    void etagFilterRunsInsideTheAuditFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg = config.recentFeedEtagFilter();

        // Higher order value = runs later (inner). The audit filter is
        // pinned one step earlier so it observes the final status.
        assertThat(reg.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(HttpCachingConfig.AUDIT_FILTER_ORDER).isLessThan(reg.getOrder());
    }
}
