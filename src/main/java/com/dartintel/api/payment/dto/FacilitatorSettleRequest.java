package com.dartintel.api.payment.dto;

public record FacilitatorSettleRequest(
        int x402Version,
        PaymentPayload paymentPayload,
        PaymentRequirement paymentRequirements
) {
}
