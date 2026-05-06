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
  const chainId = Number(requirement.network.split(':')[1]);
  if (!Number.isFinite(chainId)) {
    throw new PaymentError(
      'invalid_network',
      `cannot parse chainId from "${requirement.network}"`,
    );
  }
  const domainName = requirement.extra?.name ?? 'USDC';
  const domainVersion = requirement.extra?.version ?? '2';
  if (domainName !== 'USD Coin' && domainName !== 'USDC') {
    // eslint-disable-next-line no-console
    console.warn(
      `x402: server-declared EIP-712 token name "${domainName}" is not the ` +
        'canonical "USD Coin" (Base mainnet) or "USDC" (Base Sepolia). ' +
        'If the server is genuine, verify the asset contract; if not, the ' +
        'wrong domain will burn the EIP-3009 nonce on-chain.',
    );
  }

  return account.signTypedData({
    domain: {
      name: domainName,
      version: domainVersion,
      chainId,
      verifyingContract: requirement.asset as Hex,
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
 */
export function decodeSettlementHeader(value: string | null | undefined): unknown {
  if (!value) return null;
  try {
    return JSON.parse(base64Decode(value));
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
