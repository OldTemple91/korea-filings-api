package com.dartintel.api.payment;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Startup-time fail-fast on x402 misconfiguration. The
 * {@link X402Properties} record carries a handful of values where a
 * silent default produces a runtime payment failure that is hard to
 * trace from the facilitator's opaque {@code invalid_payload} reject.
 *
 * <ul>
 *   <li>{@code recipient-address} blank → every 402 challenge points
 *       at the zero address. Fail at boot.</li>
 *   <li>{@code asset} blank → same.</li>
 *   <li>{@code network} blank → same.</li>
 *   <li>EIP-712 token domain on Base mainnet must be
 *       {@code "USD Coin"/"2"}; on Base Sepolia it is
 *       {@code "USDC"/"2"}. A mainnet boot with the testnet token name
 *       reverts {@code transferWithAuthorization} on-chain — opaque
 *       error for both server and client. Fail at boot when the pair
 *       does not match the declared network.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class X402PropertiesValidator {

    private static final String BASE_MAINNET = "eip155:8453";
    private static final String BASE_SEPOLIA = "eip155:84532";

    private final X402Properties props;

    @PostConstruct
    void validate() {
        require(props.network(), "x402.network");
        require(props.asset(), "x402.asset");
        require(props.recipientAddress(), "x402.recipient-address");

        // Reject the well-known zero address so a missed env var doesn't
        // ship 402 challenges that pay nobody.
        if ("0x0000000000000000000000000000000000000000".equalsIgnoreCase(props.recipientAddress())) {
            throw new IllegalStateException(
                    "x402.recipient-address is the zero address — set X402_RECIPIENT_ADDRESS in .env");
        }

        // EIP-712 token domain check.
        String name = props.tokenName();
        String version = props.tokenVersion();
        require(name, "x402.token-name");
        require(version, "x402.token-version");

        if (BASE_MAINNET.equals(props.network()) && !"USD Coin".equals(name)) {
            throw new IllegalStateException(
                    "x402.token-name must be \"USD Coin\" on Base mainnet (got \"" + name
                    + "\"). The on-chain USDC contract returns this exact string from name(); "
                    + "any mismatch reverts transferWithAuthorization with an opaque error.");
        }
        if (BASE_SEPOLIA.equals(props.network()) && !"USDC".equals(name)) {
            throw new IllegalStateException(
                    "x402.token-name must be \"USDC\" on Base Sepolia (got \"" + name + "\")");
        }

        log.info("x402 config validated: network={} asset={} payTo={} tokenDomain={}/{}",
                props.network(), props.asset(), props.recipientAddress(), name, version);
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must be set");
        }
    }
}
