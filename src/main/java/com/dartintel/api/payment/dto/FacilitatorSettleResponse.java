package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FacilitatorSettleResponse(
        boolean success,
        String errorReason,
        String transaction,
        String network,
        String payer
) {
}
