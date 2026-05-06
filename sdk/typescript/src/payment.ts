/**
 * x402 payment internals — TypeScript port of the Python SDK's
 * `_payment.py`.
 *
 * What lives here:
 * - parsing the 402 `accepts[]` block,
 * - building the EIP-3009 TransferWithAuthorization message,
 * - signing it with the caller's private key (EIP-712, via viem),
 * - serialising the signed `PaymentPayload` as the base64
 *   `PAYMENT-SIGNATURE` header value the x402 v2 transport spec
 *   expects.
 *
 * Deliberately does not talk to the facilitator from the client —
 * the server's interceptor submits the signed payment, which keeps
 * the SDK small and prevents leaking facilitator URL choices into
 * user code.
 */

import { bytesToHex, type Hex, type LocalAccount } from 'viem';
import { PaymentError } from './errors.js';
import type { PaymentRequirement } from './models.js';

export const X402_VERSION = 2;

/**
 * EIP-712 domain values the SDK accepts for known chains. The 402
 * challenge sends server-supplied `extra.name`, `extra.version`,
 * and `asset` (verifyingContract); a malicious or MITM-injected
 * server could substitute these to trick the client into signing
 * a `transferWithAuthorization` against a different USDC contract,
 * a different chain, or a custom contract with no replay protection.
 *
 * For known chains we ignore the server-supplied values entirely
 * and use the canonical USDC deployment. For unknown chains we
 * fall back to the server-supplied values with a console.warn —
 * keeps forward compatibility with future test networks without
 * silently expanding the trusted-domain set.
 *
 * Address case is the EIP-55 checksum form. The comparison is
 * case-insensitive so a server that sends a lowercase asset still
 * matches.
 */
const KNOWN_DOMAINS: Record<string, { name: string; version: string; verifyingContract: `0x${string}` }> = {
  // Base mainnet — eip155:8453, USDC (Native)
  '8453': {
    name: 'USD Coin',
    version: '2',
    verifyingContract: '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913',
  },
  // Base Sepolia — eip155:84532, USDC
  '84532': {
    name: 'USDC',
    version: '2',
    verifyingContract: '0x036CbD53842c5426634e7929541eC2318f3dCF7e',
  },
};

/**
 * Pick the first payment requirement the server offers. Today the
 * server only ever offers one (USDC on whatever network it's
 * configured for), so we take index 0. If the server starts offering
 * multiple schemes, this is the place to prefer the cheaper one.
 */
export function selectRequirement(accepts: PaymentRequirement[]): PaymentRequirement {
  if (!accepts || accepts.length === 0) {
    throw new PaymentError('empty_accepts', 'server returned 402 with no payment options');
  }
  return accepts[0]!;
}

export interface Authorization {
  from: Hex;
  to: Hex;
  value: bigint;
  validAfter: bigint;
  validBefore: bigint;
  nonce: Hex;
}

/**
 * Assemble the EIP-3009 authorization fields.
 *
 * `validAfter` gets a 10-second backfill so a payer clock that is
 * slightly behind the facilitator's doesn't cause a rejection.
 * `validBefore` uses the `maxTimeoutSeconds` the server advertised.
 * `nonce` is random 32 bytes — the facilitator rejects replays.
 */
export function buildAuthorization(
  payerAddress: Hex,
  requirement: PaymentRequirement,
): Authorization {
  const now = Math.floor(Date.now() / 1000);
  return {
    from: payerAddress,
    to: requirement.payTo as Hex,
    value: BigInt(requirement.amount),
    validAfter: BigInt(Math.max(0, now - 10)),
    validBefore: BigInt(now + requirement.maxTimeoutSeconds),
    nonce: randomNonce(),
  };
}

function randomNonce(): Hex {
  // 32 random bytes via the Web Crypto API — available in Node 18+
  // (built-in `crypto.webcrypto`) and every modern browser, so no
  // node:crypto fallback needed.
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

/**
 * Produce the 65-byte EIP-712 signature over the authorization.
 *
 * The EIP-712 domain `name` and `version` come from the server's
 * 402 response (`requirement.extra`). A wrong domain causes the
 * on-chain `transferWithAuthorization` to revert with an opaque
 * error and consumes the signature's nonce, so a malicious or
 * misconfigured server could trick the SDK into burning nonces by
 * sending a domain that doesn't match any deployed USDC contract.
 *
 * Warn (not hard-fail, to avoid breaking forward compat with future
 * networks) when the declared `extra.name` is not one of the two
 * USDC values we know: "USD Coin" (Base mainnet) or "USDC" (Base
 * Sepolia).
 */
export async function signEip3009(
  account: LocalAccount,
  requirement: PaymentRequirement,
  authorization: Authorization,
): Promise<Hex> {
  // Strict eip155:<chainId> shape so a malformed network string is
  // rejected before signing, instead of relying on the accidental
  // robustness of Number.isFinite.
  const networkMatch = requirement.network.match(/^eip155:(\d+)$/);
  if (!networkMatch) {
    throw new PaymentError(
      'invalid_network',
      `expected CAIP-2 eip155:<chainId> shape, got "${requirement.network}"`,
    );
  }
  const chainId = Number(networkMatch[1]);

  // For known chains we override the server-supplied domain fields
  // with our allowlisted values — defends against a malicious server
  // injecting a different USDC contract, a different EIP-712 domain
  // version, or a custom contract with no replay protection. The
  // server-supplied requirement.asset MUST also match the
  // allowlist's verifyingContract; if it doesn't, we hard-fail
  // rather than sign for a contract that wasn't authorized in code.
  let domainName: string;
  let domainVersion: string;
  let verifyingContract: Hex;

  const known = KNOWN_DOMAINS[String(chainId)];
  if (known) {
    if (requirement.asset.toLowerCase() !== known.verifyingContract.toLowerCase()) {
      // Structured detail an LLM agent can reason about without
      // string-parsing the human-readable message: the agent reads
      // .detail.expected vs .detail.observed and decides whether
      // to refuse the server (mismatch is hostile or
      // misconfigured) or to fall back to a different endpoint.
      throw new PaymentError(
        'asset_mismatch',
        {
          expected: known.verifyingContract,
          observed: requirement.asset,
          chainId,
          recommendation: 'refuse_server',
          rationale:
            'Refusing to sign — a wrong verifyingContract burns the EIP-3009 ' +
            'nonce on-chain or, worse, signs a transfer against an unrelated contract.',
        },
      );
    }
    domainName = known.name;
    domainVersion = known.version;
    verifyingContract = known.verifyingContract;
  } else {
    // Unknown chain — fall through with a loud warning. Useful
    // during testnet rollout for new chains; should never fire in
    // production against Base mainnet.
    domainName = requirement.extra?.name ?? 'USDC';
    domainVersion = requirement.extra?.version ?? '2';
    verifyingContract = requirement.asset as Hex;
    // eslint-disable-next-line no-console
    console.warn(
      `x402: chain ${chainId} is not in the SDK's allowlist of known ` +
        `domains. Signing with server-supplied name="${domainName}" ` +
        `version="${domainVersion}" asset=${requirement.asset}. If you ` +
        'do not control this server, consider hard-coding the chain in ' +
        'KNOWN_DOMAINS or refusing the call entirely.',
    );
  }

  return account.signTypedData({
    domain: {
      name: domainName,
      version: domainVersion,
      chainId,
      verifyingContract,
    },
    types: {
      TransferWithAuthorization: [
        { name: 'from', type: 'address' },
        { name: 'to', type: 'address' },
        { name: 'value', type: 'uint256' },
        { name: 'validAfter', type: 'uint256' },
        { name: 'validBefore', type: 'uint256' },
        { name: 'nonce', type: 'bytes32' },
      ],
    },
    primaryType: 'TransferWithAuthorization',
    message: {
      from: authorization.from,
      to: authorization.to,
      value: authorization.value,
      validAfter: authorization.validAfter,
      validBefore: authorization.validBefore,
      nonce: authorization.nonce,
    },
  });
}

/**
 * Base64-encode the signed payload into the `PAYMENT-SIGNATURE`
 * header value. Wire format is identical to the v1 `X-PAYMENT`
 * header — only the HTTP header name changed in x402 v2 transport.
 */
export function buildPaymentSignatureHeader(
  resourceUrl: string,
  requirement: PaymentRequirement,
  authorization: Authorization,
  signature: Hex,
): string {
  const payload = {
    x402Version: X402_VERSION,
    resource: {
      url: resourceUrl,
      description: requirement.description ?? '',
      mimeType: 'application/json',
    },
    accepted: requirement,
    payload: {
      signature,
      authorization: {
        from: authorization.from,
        to: authorization.to,
        // EIP-3009 expects string-form integers in the wire payload
        // (the on-chain contract takes uint256). bigints serialise
        // to the right shape via toString().
        value: authorization.value.toString(),
        validAfter: authorization.validAfter.toString(),
        validBefore: authorization.validBefore.toString(),
        nonce: authorization.nonce,
      },
    },
  };
  return base64Encode(JSON.stringify(payload));
}

/**
 * Decode the base64 settlement proof header, or return `null` on any
 * decode/parse failure. Both `PAYMENT-RESPONSE` (v2) and
 * `X-PAYMENT-RESPONSE` (legacy) carry the same shape, so callers
 * pass either header value through this same function.
 *
 * <p>Returns `null` if the decoded payload is not a JSON object with
 * a boolean `success` field — defends against a malicious server
 * stuffing a non-object (string, number, null) or a multi-megabyte
 * `errorReason` blob into the header. The caller treats `null` as
 * "no settlement proof was attached" and falls back to the response
 * body for error detail.
 *
 * <p>Caps the decoded payload size at 16 KB; a real settlement proof
 * is ~200 bytes, so anything larger is either a server bug or a
 * deliberate denial-of-service attempt against client-side log /
 * error-message storage.
 */
export function decodeSettlementHeader(value: string | null | undefined): unknown {
  if (!value) return null;
  // 16 KB cap — settlement proofs are well under 1 KB in practice.
  if (value.length > 16 * 1024) return null;
  try {
    const decoded: unknown = JSON.parse(base64Decode(value));
    if (typeof decoded !== 'object' || decoded === null) return null;
    if (typeof (decoded as { success?: unknown }).success !== 'boolean') return null;
    return decoded;
  } catch {
    return null;
  }
}

/**
 * Cross-runtime base64 encode. `btoa` exists in Node 18+ and every
 * browser, but it only handles ASCII; route through TextEncoder so
 * the JSON's potential non-ASCII chars (Korean company names,
 * curly quotes) survive. Same trick the WHATWG `btoa` itself does
 * for `Uint8Array` input.
 */
function base64Encode(s: string): string {
  const bytes = new TextEncoder().encode(s);
  let binary = '';
  for (const b of bytes) {
    binary += String.fromCharCode(b);
  }
  return btoa(binary);
}

function base64Decode(s: string): string {
  const binary = atob(s);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}
