package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry of the facilitator's {@code EXTENSION-RESPONSES} header
 * (base64 JSON keyed by extension id). For {@code bazaar} the status
 * is {@code success}, {@code processing} or {@code rejected}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtensionResponse(String status, String rejectedReason) {
}
