package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Body returned by a protected endpoint when it responds with HTTP 402.
 * Clients pick a {@link PaymentRequirement} from {@code accepts}, sign
 * authorisation for it, and retry the original request with the base64
 * payload in the {@code X-PAYMENT} header.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentRequirementsBody(
        int x402Version,
        String error,
        ResourceInfo resource,
        List<PaymentRequirement> accepts
) {
}
