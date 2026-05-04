package com.dartintel.api.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level tests on {@link X402PropertiesValidator}. Driven via direct
 * instantiation rather than {@code @SpringBootTest} so failures show
 * up as plain {@link IllegalStateException}s — Spring would otherwise
 * wrap them in {@code BeanCreationException} and the assertion has to
 * dig through {@code getCause()}.
 */
class X402PropertiesValidatorTest {

    private static X402Properties props(String network, String tokenName, String tokenVersion,
                                        String recipient, String asset) {
        return new X402Properties(
                "https://example.com/facilitator",
                network,
                recipient,
                asset,
                tokenName,
                tokenVersion,
                60,
                new X402Properties.Timeout(5000, 10000),
                new X402Properties.Replay(3600),
                new X402Properties.Cdp(null, null)
        );
    }

    @Test
    void mainnetBootFailsOnTestnetTokenName() {
        // The exact misconfig X402PropertiesValidator exists to catch:
        // Base mainnet network with the testnet token name. The on-chain
        // USDC contract returns "USD Coin", not "USDC", so signing with
        // the wrong domain reverts transferWithAuthorization. Validator
        // must abort startup before any 402 challenge is built.
        var v = new X402PropertiesValidator(props(
                "eip155:8453", "USDC", "2",
                "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        ));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USD Coin");
    }

    @Test
    void zeroAddressRecipientIsRejected() {
        var v = new X402PropertiesValidator(props(
                "eip155:84532", "USDC", "2",
                "0x0000000000000000000000000000000000000000",
                "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        ));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero address");
    }

    @Test
    void trailingWhitespaceInNetworkIsStrippedBeforeMainnetCheck() {
        // Common .env copy-paste mistake: trailing space on a value.
        // Validator must `.strip()` before equality, otherwise the
        // mainnet/testnet token check is skipped and a wrong-domain
        // boot ships silently.
        var v = new X402PropertiesValidator(props(
                "eip155:8453 ", " USDC ", "2",
                "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        ));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USD Coin");
    }

    @Test
    void blankFieldRejectedWithKeyNameInMessage() {
        var v = new X402PropertiesValidator(props(
                "eip155:84532", "USDC", "2",
                "  ",  // blank recipient
                "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        ));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x402.recipient-address");
    }

    @Test
    void validMainnetConfigPasses() {
        var v = new X402PropertiesValidator(props(
                "eip155:8453", "USD Coin", "2",
                "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        ));
        // No exception expected.
        v.validate();
    }

    @Test
    void unknownNetworkLogsButDoesNotFail() {
        // Forward-compat: a future CAIP-2 id should not require a
        // code change. Validator skips token-name check and warns.
        var v = new X402PropertiesValidator(props(
                "eip155:99999", "anything-non-blank", "1",
                "0x1111111111111111111111111111111111111111",
                "0x2222222222222222222222222222222222222222"
        ));
        v.validate();
        assertThat(true).isTrue(); // reached, no throw
    }
}
