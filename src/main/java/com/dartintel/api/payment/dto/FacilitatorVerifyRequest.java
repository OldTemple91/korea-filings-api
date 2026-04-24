package com.dartintel.api.payment.dto;

public record FacilitatorVerifyRequest(
        int x402Version,
        PaymentPayload paymentPayload,
        PaymentRequirement paymentRequirements
) {
}
