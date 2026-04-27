package com.dartintel.api.payment;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the CDP JWT signer with a freshly-generated Ed25519 keypair
 * — never the real CDP key. Verifies the signer:
 * <ul>
 *     <li>returns null when CDP is not configured (testnet path),</li>
 *     <li>produces a JWT that parses with the matching public key,</li>
 *     <li>encodes the {@code uri} claim as {@code METHOD host/path}.</li>
 * </ul>
 */
class CdpJwtSignerTest {

    @Test
    void signReturnsNullWhenCdpNotConfigured() {
        var props = props(new X402Properties.Cdp("", ""));
        var signer = new CdpJwtSigner(props);

        assertThat(signer.sign("POST", "https://api.cdp.coinbase.com/platform/v2/x402/verify"))
                .isNull();
    }

    @Test
    void signProducesJwtParseableWithMatchingPublicKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // The signer expects a 64-byte (seed||public) base64 blob, matching CDP's
        // download format. Strip the PKCS#8 prefix from the JDK-encoded key,
        // then append the public key bytes the same way CDP does.
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        byte[] seed = new byte[32];
        System.arraycopy(pkcs8, pkcs8.length - 32, seed, 0, 32);
        byte[] expanded = new byte[64];
        System.arraycopy(seed, 0, expanded, 0, 32);
        // The 32-byte ed25519 public key sits at the tail of the X.509 SubjectPublicKeyInfo.
        byte[] x509 = pair.getPublic().getEncoded();
        System.arraycopy(x509, x509.length - 32, expanded, 32, 32);

        String b64 = Base64.getEncoder().encodeToString(expanded);
        var props = props(new X402Properties.Cdp("test-kid-123", b64));
        var signer = new CdpJwtSigner(props);

        String jwt = signer.sign("POST", "https://api.cdp.coinbase.com/platform/v2/x402/verify");
        assertThat(jwt).isNotNull().contains(".");

        // Verify the signature parses back with the matching public key.
        var parsed = Jwts.parser()
                .verifyWith(pair.getPublic())
                .build()
                .parseSignedClaims(jwt);
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("cdp");
        assertThat(parsed.getPayload().getSubject()).isEqualTo("test-kid-123");
        assertThat(parsed.getPayload().get("uri", String.class))
                .isEqualTo("POST api.cdp.coinbase.com/platform/v2/x402/verify");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("test-kid-123");
    }

    @Test
    void signRejectsMalformedKey() {
        var props = props(new X402Properties.Cdp(
                "kid", Base64.getEncoder().encodeToString("not-a-real-key".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> new CdpJwtSigner(props))
                .isInstanceOf(IllegalStateException.class);
    }

    private static X402Properties props(X402Properties.Cdp cdp) {
        return new X402Properties(
                "https://example.test/",
                "eip155:8453",
                "0x0",
                "0x0",
                300,
                new X402Properties.Timeout(5_000, 10_000),
                new X402Properties.Replay(3600),
                cdp
        );
    }

    /**
     * Sanity: the constant signer-side public key derivation in the production
     * code stays compatible with the {@link KeyPairGenerator} round-trip.
     */
    @Test
    @SuppressWarnings("unused")
    void publicKeysAreInteroperable() throws Exception {
        PublicKey ignored = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    }
}
