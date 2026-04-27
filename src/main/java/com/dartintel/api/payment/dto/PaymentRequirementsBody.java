package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Body returned by a protected endpoint when it responds with HTTP 402.
 * Clients pick a {@link PaymentRequirement} from {@code accepts}, sign
 * authorisation for it, and retry the original request with the base64
 * payload in the {@code X-PAYMENT} header.
 *
 * <p>{@code extensions} is a map keyed by extension identifier (per the
 * x402 v2 spec, e.g. {@code "bazaar"}) — kept as a free-form
 * {@code Map<String, Object>} because every extension defines its own
 * shape. Indexers like x402scan refuse to register endpoints in strict
 * mode without a {@code bazaar} entry that declares the input schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentRequirementsBody(
        int x402Version,
        String error,
        ResourceInfo resource,
        List<PaymentRequirement> accepts,
        Map<String, Object> extensions
) {
}
