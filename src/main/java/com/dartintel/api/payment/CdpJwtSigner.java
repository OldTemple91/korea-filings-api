package com.dartintel.api.payment;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signs short-lived Ed25519 JWTs for the Coinbase CDP x402 facilitator.
 *
 * <p>CDP rejects every API call without an {@code Authorization: Bearer
 * <jwt>} header on mainnet. The JWT must be:
 * <ul>
 *     <li>signed with EdDSA (Ed25519) using the API key,</li>
 *     <li>issued by {@code cdp},</li>
 *     <li>scoped to a single HTTP method + URI via the {@code uri} claim,</li>
 *     <li>valid for at most ~120 seconds.</li>
 * </ul>
 *
 * <p>The bean is unconditional but the JWT it returns is empty when
 * {@link X402Properties.Cdp#isConfigured()} is false — the
 * {@link FacilitatorClient} skips attaching the header in that case so
 * the testnet path stays unauthenticated.
 *
 * <p>The CDP JSON download ships a 64-byte Ed25519 keypair (seed + public)
 * base64-encoded. Java's Ed25519 {@link java.security.Signature} only
 * needs the 32-byte seed wrapped in a PKCS#8 OneAsymmetricKey envelope —
 * we build that envelope by prepending the standard Ed25519 PKCS#8 prefix
 * to the seed bytes.
 */
@Component
@Slf4j
public class CdpJwtSigner {

    /**
     * The fixed PKCS#8 prefix for an Ed25519 OneAsymmetricKey:
     *   SEQUENCE                    {30 2e}
     *     INTEGER 0                 {02 01 00}            (version)
     *     SEQUENCE                  {30 05}
     *       OID 1.3.101.112         {06 03 2b 65 70}      (Ed25519)
     *     OCTET STRING (length 34)  {04 22}
     *       OCTET STRING (length 32){04 20}               (key bytes follow)
     */
    private static final byte[] PKCS8_ED25519_PREFIX = HexFormat.of()
            .parseHex("302e020100300506032b657004220420");

    private static final Duration TOKEN_TTL = Duration.ofSeconds(120);

    private final X402Properties.Cdp cdp;
    private final PrivateKey privateKey;

    public CdpJwtSigner(X402Properties props) {
        this.cdp = props.cdp();
        this.privateKey = cdp.isConfigured() ? loadEd25519Key(cdp.privateKey()) : null;
        if (privateKey != null) {
            log.info("CDP facilitator JWT signer initialised (kid={})", maskedKid(cdp.keyId()));
        } else {
            log.info("CDP facilitator JWT signer not configured — testnet auth-less mode");
        }
    }

    /**
     * Build a Bearer JWT scoped to a single facilitator request.
     *
     * @param method  HTTP method (uppercase, e.g. {@code "POST"})
     * @param fullUrl Full URL including scheme and host the request will hit
     * @return JWT compact-serialised, or {@code null} when CDP is not configured
     */
    public String sign(String method, String fullUrl) {
        if (privateKey == null) {
            return null;
        }
        URI uri = URI.create(fullUrl);
        // CDP wants the host + path, no scheme, in the uri claim.
        String uriClaim = "%s %s%s".formatted(method.toUpperCase(), uri.getHost(), uri.getRawPath());
        Instant now = Instant.now();
        return Jwts.builder()
                .header()
                    .keyId(cdp.keyId())
                    .add("nonce", randomNonce())
                .and()
                .issuer("cdp")
                .subject(cdp.keyId())
                .issuedAt(java.util.Date.from(now))
                .notBefore(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(TOKEN_TTL)))
                .claim("uri", uriClaim)
                .signWith(privateKey, Jwts.SIG.EdDSA)
                .compact();
    }

    private PrivateKey loadEd25519Key(String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
        // CDP exports the 64-byte expanded keypair (seed || pub). Take the seed.
        // Some accounts may receive a bare 32-byte seed; both shapes are accepted.
        byte[] seed = decoded.length >= 32
                ? java.util.Arrays.copyOfRange(decoded, 0, 32)
                : decoded;
        if (seed.length != 32) {
            throw new IllegalStateException(
                    "Ed25519 seed must be 32 bytes; got " + seed.length + ". "
                            + "Check the CDP private key payload format.");
        }
        byte[] pkcs8 = new byte[PKCS8_ED25519_PREFIX.length + 32];
        System.arraycopy(PKCS8_ED25519_PREFIX, 0, pkcs8, 0, PKCS8_ED25519_PREFIX.length);
        System.arraycopy(seed, 0, pkcs8, PKCS8_ED25519_PREFIX.length, 32);
        try {
            return java.security.KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load Ed25519 private key for CDP JWT signing. "
                            + "Confirm X402_CDP_PRIVATE_KEY is the base64 string from "
                            + "the CDP Server API Key JSON download.",
                    e);
        }
    }

    /**
     * Single shared SecureRandom — `new SecureRandom()` per call wastes
     * an allocation and on some JVMs occasionally blocks on the
     * initial entropy collection. The instance is thread-safe and we
     * only need 16 bytes per JWT (128-bit nonce, well above any
     * collision-of-concern threshold).
     */
    private static final java.security.SecureRandom NONCE_RNG = new java.security.SecureRandom();

    private static String randomNonce() {
        byte[] bytes = new byte[16];
        NONCE_RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String maskedKid(String kid) {
        if (kid == null || kid.length() <= 8) {
            return "<short>";
        }
        return kid.substring(0, 4) + "…" + kid.substring(kid.length() - 4);
    }
}
