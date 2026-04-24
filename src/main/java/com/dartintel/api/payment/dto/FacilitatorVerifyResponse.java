package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FacilitatorVerifyResponse(
        boolean isValid,
        String invalidReason,
        String payer,
        String scheme,
        String network
) {
}
