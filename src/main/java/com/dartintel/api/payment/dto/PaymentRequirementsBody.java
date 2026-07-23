package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Body returned by a protected endpoint when it responds with HTTP 402.
 * Clients pick a {@link PaymentRequirement} from {@code accepts}, sign
 * authorisation for it, and retry the original request with the base64
 * payload in the {@code PAYMENT-SIGNATURE} header (or the legacy
 * {@code X-PAYMENT} alias). The same payload is also emitted in the
 * {@code PAYMENT-REQUIRED} response header per x402 v2 transport spec
 * for header-only consumers (x402scan and similar indexers).
 *
 * <p>{@code extensions} is a map keyed by extension identifier (per the
 * x402 v2 spec, e.g. {@code "bazaar"}) — kept as a free-form
 * {@code Map<String, Object>} because every extension defines its own
 * shape. Indexers like x402scan refuse to register endpoints in strict
 * mode without a {@code bazaar} entry that declares the input schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentRequirementsBody(
        int x402Version,
        String error,
        ResourceInfo resource,
        List<PaymentRequirement> accepts,
        Map<String, Object> extensions,
        // Round-18b: plain-language escape hatch for the human developer
        // who hits the paywall with curl / requests and has no x402
        // client. Every observed organic prospect (four in July alone)
        // died at exactly this response with zero retries — the 402 was
        // written only for machines. Spec-aware clients ignore unknown
        // fields, so the extra key is harmless to agents.
        HowToPay howToPay
) {

    /** Human-readable pointers from a 402 to a working paid call. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HowToPay(
            String summary,
            String pythonQuickstart,
            String typescriptQuickstart,
            String freeSample,
            String docs
    ) {
        public static HowToPay standard() {
            return new HowToPay(
                    "This endpoint is paid per call via the x402 protocol (USDC on Base). "
                            + "No API key, no signup — a funded wallet and one of the SDKs below "
                            + "handle the 402 → sign → retry flow automatically.",
                    "pip install koreafilings  # then: Client(private_key=...).get_summary(rcpt_no)",
                    "npm install koreafilings  # then: new KoreaFilings({ privateKey }).getSummary(rcptNo)",
                    "GET https://api.koreafilings.com/v1/disclosures/sample — the exact paid "
                            + "response shape, free, no wallet needed",
                    "https://api.koreafilings.com/llms.txt"
            );
        }
    }
}
