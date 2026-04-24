package com.dartintel.api.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("x402")
public record X402Properties(
        String facilitatorUrl,
        String network,
        String recipientAddress,
        String asset,
        int maxTimeoutSeconds,
        Timeout timeout,
        Replay replay
) {

    public record Timeout(int connectMs, int readMs) {
    }

    public record Replay(int ttlSeconds) {
    }
}
