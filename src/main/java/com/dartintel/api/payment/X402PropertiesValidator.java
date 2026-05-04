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
        // Trim before any equality check — `.env` files are often
        // edited by hand and trailing whitespace from a copy-paste
        // would otherwise pass `isBlank()` and fail the strict
        // `equals()` checks below, silently bypassing the
        // mainnet/testnet token-domain guard.
        String network = strip(props.network());
        String asset = strip(props.asset());
        String recipient = strip(props.recipientAddress());
        String tokenName = strip(props.tokenName());
        String tokenVersion = strip(props.tokenVersion());

        require(network, "x402.network");
        require(asset, "x402.asset");
        require(recipient, "x402.recipient-address");
        require(tokenName, "x402.token-name");
        require(tokenVersion, "x402.token-version");

        // Reject the well-known zero address so a missed env var doesn't
        // ship 402 challenges that pay nobody.
        if ("0x0000000000000000000000000000000000000000".equalsIgnoreCase(recipient)) {
            throw new IllegalStateException(
                    "x402.recipient-address is the zero address — set X402_RECIPIENT_ADDRESS in .env");
        }

        if (BASE_MAINNET.equals(network) && !"USD Coin".equals(tokenName)) {
            throw new IllegalStateException(
                    "x402.token-name must be \"USD Coin\" on Base mainnet (got \"" + tokenName
                    + "\"). The on-chain USDC contract returns this exact string from name(); "
                    + "any mismatch reverts transferWithAuthorization with an opaque error.");
        }
        if (BASE_SEPOLIA.equals(network) && !"USDC".equals(tokenName)) {
            throw new IllegalStateException(
                    "x402.token-name must be \"USDC\" on Base Sepolia (got \"" + tokenName + "\")");
        }

        if (!BASE_MAINNET.equals(network) && !BASE_SEPOLIA.equals(network)) {
            // Not a hard fail — leaves room for other CAIP-2 ids to be
            // added without forcing a code change — but log loudly so
            // misconfig is visible at boot rather than at first 402.
            log.warn("x402 network {} is not Base mainnet or Sepolia; "
                    + "token-name validation skipped, on-chain failures will be opaque.", network);
        }

        log.info("x402 config validated: network={} asset={} payTo={} tokenDomain={}/{}",
                network, asset, recipient, tokenName, tokenVersion);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static void require(String value, String key) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(key + " must be set (non-blank)");
        }
    }
}
