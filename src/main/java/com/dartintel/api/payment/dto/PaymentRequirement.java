package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * A single entry inside the {@code accepts} array of a 402 response,
 * and the shape facilitator {@code /verify} and {@code /settle} expect
 * under their {@code paymentRequirements} field. Matches the "exact"
 * scheme for EVM networks (x402 v2 spec).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequirement(
        String scheme,
        String network,
        String amount,
        String asset,
        String payTo,
        int maxTimeoutSeconds,
        Map<String, Object> extra
) {
}
