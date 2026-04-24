package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body encoded in the client's {@code X-PAYMENT} header (base64 of JSON).
 * "accepted" is the single requirement the client chose out of the
 * server's {@code accepts} list, echoed back so the facilitator can
 * match signature scope against server-declared scope.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentPayload(
        int x402Version,
        ResourceInfo resource,
        PaymentRequirement accepted,
        EvmExactPayload payload
) {
}
