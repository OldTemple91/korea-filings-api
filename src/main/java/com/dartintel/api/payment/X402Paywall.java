package com.dartintel.api.payment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring x402 payment. The interceptor
 * checks for a valid {@code X-PAYMENT} header, verifies it with the
 * configured facilitator, and on a successful 2xx response triggers
 * settlement. Price is denominated in USDC as a decimal string so
 * humans can read it (e.g. {@code "0.005"}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface X402Paywall {

    /** USDC price as a decimal string — converted to atomic 6-decimal units at runtime. */
    String priceUsdc();

    /** Optional human-readable description for the 402 response body. */
    String description() default "";
}
