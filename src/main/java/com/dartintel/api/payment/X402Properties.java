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
        Replay replay,
        Cdp cdp
) {

    public record Timeout(int connectMs, int readMs) {
    }

    public record Replay(int ttlSeconds) {
    }

    /**
     * Coinbase CDP facilitator credentials. Both fields are blank for the
     * public testnet facilitator at x402.org (which is unauthenticated).
     * On mainnet, the {@link FacilitatorClient} signs an Ed25519 JWT per
     * request using these and adds it as a Bearer token.
     *
     * @param keyId       The CDP API key id (UUID), used as the JWT {@code kid}
     *                    and {@code sub}.
     * @param privateKey  Base64-encoded 64-byte Ed25519 keypair (seed + public).
     *                    Java's {@code java.security.Signature} only needs the
     *                    first 32 bytes; the second half is the public key the
     *                    facilitator already knows.
     */
    public record Cdp(String keyId, String privateKey) {

        public boolean isConfigured() {
            return keyId != null && !keyId.isBlank()
                    && privateKey != null && !privateKey.isBlank();
        }
    }
}
