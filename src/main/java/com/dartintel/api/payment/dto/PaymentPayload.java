package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Body encoded in the client's {@code PAYMENT-SIGNATURE} header
 * (or the legacy {@code X-PAYMENT} alias) as base64 of JSON.
 *
 * <p>{@code accepted} is the single requirement the client chose out
 * of the server's {@code accepts} list, echoed back so the
 * facilitator can match signature scope against server-declared
 * scope.
 *
 * <p>{@code extensions} carries any extension blocks (e.g.
 * {@code bazaar}) that the client read from the 402 challenge and
 * is acknowledging. Per
 * <a href="https://github.com/coinbase/x402/blob/main/specs/x402-specification-v2.md">
 * x402 v2 §5.2.2</a> the field is optional, but a strict facilitator
 * (or future bazaar binding) may verify the client echoed back the
 * server-declared extension set. Without this field declared,
 * Jackson silently dropped the echo on the way to verify, leaving
 * the facilitator unable to enforce extension-bound logic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentPayload(
        int x402Version,
        ResourceInfo resource,
        PaymentRequirement accepted,
        EvmExactPayload payload,
        Map<String, Object> extensions
) {

    /**
     * Compatibility constructor for callers that do not pass
     * extensions (e.g. tests, the 0.2.x SDK release).
     */
    public PaymentPayload(int x402Version,
                          ResourceInfo resource,
                          PaymentRequirement accepted,
                          EvmExactPayload payload) {
        this(x402Version, resource, accepted, payload, null);
    }
}
