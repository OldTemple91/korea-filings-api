package com.dartintel.api.payment.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceInfoTest {

    private static final List<String> TAGS = List.of("korea", "dart");

    @Test
    void withBrandingIfAbsentFillsMissingFields() {
        ResourceInfo bare = new ResourceInfo("https://x/summary", "desc", "application/json");

        ResourceInfo out = bare.withBrandingIfAbsent("Korea Filings", TAGS, "https://x/logo.png");

        assertThat(out.url()).isEqualTo("https://x/summary");
        assertThat(out.description()).isEqualTo("desc");
        assertThat(out.mimeType()).isEqualTo("application/json");
        assertThat(out.serviceName()).isEqualTo("Korea Filings");
        assertThat(out.tags()).isEqualTo(TAGS);
        assertThat(out.iconUrl()).isEqualTo("https://x/logo.png");
    }

    @Test
    void withBrandingIfAbsentKeepsClientSuppliedValues() {
        // A client that echoed (or set) its own branding keeps it; only
        // the gaps are filled.
        ResourceInfo partial = new ResourceInfo("https://x/summary", "desc", "application/json",
                "Client Name", null, null);

        ResourceInfo out = partial.withBrandingIfAbsent("Korea Filings", TAGS, "https://x/logo.png");

        assertThat(out.serviceName()).isEqualTo("Client Name");
        assertThat(out.tags()).isEqualTo(TAGS);
        assertThat(out.iconUrl()).isEqualTo("https://x/logo.png");
    }

    @Test
    void compatConstructorLeavesBrandingNull() {
        ResourceInfo bare = new ResourceInfo("https://x/summary", "desc", "application/json");

        assertThat(bare.serviceName()).isNull();
        assertThat(bare.tags()).isNull();
        assertThat(bare.iconUrl()).isNull();
    }
}
