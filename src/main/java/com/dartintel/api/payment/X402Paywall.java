package com.dartintel.api.payment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring x402 payment. The interceptor
 * reads the v2 {@code PAYMENT-SIGNATURE} header (falling back to the
 * legacy {@code X-PAYMENT} header for backward compatibility), verifies
 * it with the configured facilitator, and on a successful 2xx response
 * triggers settlement. Price is denominated in USDC as a decimal string
 * so humans can read it (e.g. {@code "0.005"}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface X402Paywall {

    /** USDC price (per result if {@link #pricingMode()} is PER_RESULT) as a decimal string. */
    String priceUsdc();

    /** Optional human-readable description for the 402 response body. */
    String description() default "";

    /**
     * How the final charge is computed.
     * <ul>
     *   <li>{@link Mode#FIXED} — fixed price per call, equal to {@link #priceUsdc()}.
     *       Default; matches the v1 single-summary endpoint.</li>
     *   <li>{@link Mode#PER_RESULT} — price = {@code priceUsdc} × the integer
     *       value of the query parameter named by {@link #countQueryParam()}.
     *       Used by batch endpoints (e.g. by-ticker) where the agent
     *       chooses how many results to pay for. Missing query param
     *       falls back to {@link #defaultCount()}.</li>
     * </ul>
     */
    Mode pricingMode() default Mode.FIXED;

    /**
     * Query parameter name used as the count multiplier when
     * {@link #pricingMode()} is {@link Mode#PER_RESULT}. Ignored otherwise.
     */
    String countQueryParam() default "";

    /**
     * Fallback count when the query parameter named by {@link #countQueryParam()}
     * is missing or unparsable. Ignored when {@link #pricingMode()} is FIXED.
     */
    int defaultCount() default 1;

    /**
     * Hard upper bound on the count multiplier. Requests above this
     * value are rejected upstream by Bean Validation; this constant
     * exists so the interceptor can clamp defensively even if a
     * controller forgets the {@code @Max} annotation. Ignored when
     * {@link #pricingMode()} is FIXED.
     */
    int maxCount() default 50;

    enum Mode {
        FIXED,
        PER_RESULT
    }
}
