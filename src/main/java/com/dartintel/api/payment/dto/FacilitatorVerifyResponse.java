package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FacilitatorVerifyResponse(
        boolean isValid,
        String invalidReason,
        String payer,
        String scheme,
        String network,
        Map<String, ExtensionResponse> extensionResponses
) {
    public FacilitatorVerifyResponse {
        extensionResponses = extensionResponses == null ? Map.of() : Map.copyOf(extensionResponses);
    }

    public FacilitatorVerifyResponse(boolean isValid, String invalidReason, String payer,
                                     String scheme, String network) {
        this(isValid, invalidReason, payer, scheme, network, null);
    }

    public FacilitatorVerifyResponse withExtensionResponses(Map<String, ExtensionResponse> responses) {
        return new FacilitatorVerifyResponse(isValid, invalidReason, payer, scheme, network, responses);
    }
}
