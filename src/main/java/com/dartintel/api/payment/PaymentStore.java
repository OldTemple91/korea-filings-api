package com.dartintel.api.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Replay guard for x402 payment signatures. Each verified X-PAYMENT
 * header is hashed (SHA-256 hex) and registered here with a short TTL;
 * a second request carrying the same signature is rejected before the
 * facilitator is ever called. The store also offers release() so a
 * non-2xx controller outcome does not double-charge a client that
 * retries.
 */
@Component
@RequiredArgsConstructor
public class PaymentStore {

    static final String KEY_PREFIX = "payment_sig:";

    private final StringRedisTemplate redisTemplate;
    private final X402Properties props;

    /** Returns true on first registration, false if the signature is already on file. */
    public boolean registerIfAbsent(String signatureHash) {
        Boolean set = redisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + signatureHash,
                "1",
                Duration.ofSeconds(props.replay().ttlSeconds())
        );
        return Boolean.TRUE.equals(set);
    }

    /** Frees a previously registered signature — caller must know the request did not settle. */
    public void release(String signatureHash) {
        redisTemplate.delete(KEY_PREFIX + signatureHash);
    }
}
