package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FacilitatorSettleResponse(
        boolean success,
        String errorReason,
        String transaction,
        String network,
        String payer,
        Map<String, ExtensionResponse> extensionResponses
) {
    public FacilitatorSettleResponse {
        extensionResponses = extensionResponses == null ? Map.of() : Map.copyOf(extensionResponses);
    }

    public FacilitatorSettleResponse(boolean success, String errorReason, String transaction,
                                     String network, String payer) {
        this(success, errorReason, transaction, network, payer, null);
    }

    public FacilitatorSettleResponse withExtensionResponses(Map<String, ExtensionResponse> responses) {
        return new FacilitatorSettleResponse(success, errorReason, transaction, network, payer, responses);
    }
}
