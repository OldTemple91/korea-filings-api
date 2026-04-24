package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.PaymentPayload;
import com.dartintel.api.payment.dto.PaymentRequirement;

/**
 * Result of a successful /verify round-trip, stashed on the
 * HttpServletRequest by {@link X402PaywallInterceptor} so that
 * {@link X402SettlementAdvice} can settle + log after a 2xx controller
 * response. Package-private — not part of the API surface.
 */
record VerifiedPayment(
        String signatureHash,
        PaymentPayload payload,
        PaymentRequirement requirement,
        String payer,
        String endpoint
) {
}
