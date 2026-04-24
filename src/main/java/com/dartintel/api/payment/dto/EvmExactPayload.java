package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * EIP-3009 signed authorisation carried inside {@link PaymentPayload}
 * for the "exact" EVM scheme.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvmExactPayload(String signature, Eip3009Authorization authorization) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Eip3009Authorization(
            String from,
            String to,
            String value,
            String validAfter,
            String validBefore,
            String nonce
    ) {
    }
}
