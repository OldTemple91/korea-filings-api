package com.dartintel.api.payment.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PaymentPayload#withExtensionIfAbsent} — the server-side
 * fallback that guarantees the {@code bazaar} extension reaches the
 * facilitator even when a client did not echo it (x402 v2 §5.2 says
 * clients "must include at least the info received"; the Bazaar spec
 * says cataloging does not happen without it).
 */
class PaymentPayloadTest {

    private static final Map<String, Object> SERVER_BAZAAR = Map.of(
            "info", Map.of("input", Map.of("type", "http", "method", "GET")),
            "schema", Map.of("type", "object"));

    @Test
    void withExtensionIfAbsentAddsWhenExtensionsAreNull() {
        PaymentPayload original = payload(null);

        PaymentPayload out = original.withExtensionIfAbsent("bazaar", SERVER_BAZAAR);

        assertThat(out.extensions()).containsEntry("bazaar", SERVER_BAZAAR);
        assertThat(out.resource()).isEqualTo(original.resource());
        assertThat(out.accepted()).isEqualTo(original.accepted());
        assertThat(out.payload()).isEqualTo(original.payload());
    }

    @Test
    void withExtensionIfAbsentKeepsOtherClientExtensions() {
        Map<String, Object> other = Map.of("x", 1);
        PaymentPayload original = payload(Map.of("other", other));

        PaymentPayload out = original.withExtensionIfAbsent("bazaar", SERVER_BAZAAR);

        assertThat(out.extensions())
                .containsEntry("other", other)
                .containsEntry("bazaar", SERVER_BAZAAR);
    }

    @Test
    void withExtensionIfAbsentNeverOverwritesClientEcho() {
        // The spec forbids deleting or overwriting what the client
        // sent; a client that echoed bazaar keeps its own copy.
        Map<String, Object> clientBazaar = Map.of("info", Map.of("client", "echo"));
        PaymentPayload original = payload(Map.of("bazaar", clientBazaar));

        PaymentPayload out = original.withExtensionIfAbsent("bazaar", SERVER_BAZAAR);

        assertThat(out).isSameAs(original);
        assertThat(out.extensions().get("bazaar")).isEqualTo(clientBazaar);
    }

    private static PaymentPayload payload(Map<String, Object> extensions) {
        return new PaymentPayload(
                2,
                new ResourceInfo("http://localhost/v1/disclosures/summary?rcptNo=1", "DART summary", "application/json"),
                new PaymentRequirement("exact", "eip155:8453", "5000", "0xasset", "0xpayTo", 60, Map.of()),
                new EvmExactPayload("0xsig", new EvmExactPayload.Eip3009Authorization(
                        "0xfrom", "0xto", "5000", "1", "2", "0xnonce")),
                extensions);
    }
}
