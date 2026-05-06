import { describe, it, expect, vi } from 'vitest';
import { privateKeyToAccount } from 'viem/accounts';
import { recoverTypedDataAddress } from 'viem';
import {
  buildAuthorization,
  buildPaymentSignatureHeader,
  decodeSettlementHeader,
  selectRequirement,
  signEip3009,
  X402_VERSION,
} from '../src/payment.js';
import { PaymentError } from '../src/errors.js';
import type { PaymentRequirement } from '../src/models.js';

const TEST_KEY = '0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80';
const TEST_ACCOUNT = privateKeyToAccount(TEST_KEY);

const SAMPLE_REQUIREMENT: PaymentRequirement = {
  scheme: 'exact',
  network: 'eip155:84532',
  amount: '5000',
  asset: '0x036CbD53842c5426634e7929541eC2318f3dCF7e',
  payTo: '0x8467Be164C75824246CFd0fCa8E7F7009fB8f720',
  maxTimeoutSeconds: 60,
  description: 'AI summary of a Korean DART disclosure',
  extra: { name: 'USDC', version: '2' },
};

describe('selectRequirement', () => {
  it('returns the first entry from a non-empty list', () => {
    expect(selectRequirement([SAMPLE_REQUIREMENT])).toBe(SAMPLE_REQUIREMENT);
  });

  it('throws PaymentError on an empty accepts array', () => {
    expect(() => selectRequirement([])).toThrow(PaymentError);
  });
});

describe('buildAuthorization', () => {
  it('produces the EIP-3009 fields with the expected shape', () => {
    const auth = buildAuthorization(TEST_ACCOUNT.address, SAMPLE_REQUIREMENT);
    expect(auth.from).toBe(TEST_ACCOUNT.address);
    expect(auth.to).toBe(SAMPLE_REQUIREMENT.payTo);
    expect(auth.value).toBe(BigInt(SAMPLE_REQUIREMENT.amount));
    // validBefore must be `validAfter + maxTimeout + (~10s of backfill)`
    expect(auth.validBefore - auth.validAfter).toBeGreaterThanOrEqual(
      BigInt(SAMPLE_REQUIREMENT.maxTimeoutSeconds),
    );
    // nonce must be 32-byte hex (0x + 64 chars)
    expect(auth.nonce).toMatch(/^0x[0-9a-f]{64}$/);
  });

  it('produces a fresh nonce each call', () => {
    const a = buildAuthorization(TEST_ACCOUNT.address, SAMPLE_REQUIREMENT);
    const b = buildAuthorization(TEST_ACCOUNT.address, SAMPLE_REQUIREMENT);
    expect(a.nonce).not.toBe(b.nonce);
  });
});

describe('signEip3009', () => {
  it('produces a recoverable EIP-712 signature over TransferWithAuthorization', async () => {
    const auth = buildAuthorization(TEST_ACCOUNT.address, SAMPLE_REQUIREMENT);
    const sig = await signEip3009(TEST_ACCOUNT, SAMPLE_REQUIREMENT, auth);
    expect(sig).toMatch(/^0x[0-9a-f]{130}$/); // 65 bytes hex

    // Round-trip — reconstruct the typed data the same way the on-chain
    // contract would and recover the signing address.
    const recovered = await recoverTypedDataAddress({
      domain: {
        name: 'USDC',
        version: '2',
        chainId: 84532,
        verifyingContract: SAMPLE_REQUIREMENT.asset as `0x${string}`,
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
        from: auth.from,
        to: auth.to,
        value: auth.value,
        validAfter: auth.validAfter,
        validBefore: auth.validBefore,
        nonce: auth.nonce,
      },
      signature: sig,
    });
    expect(recovered.toLowerCase()).toBe(TEST_ACCOUNT.address.toLowerCase());
  });

  it('uses the mainnet EIP-712 domain when extra.name = "USD Coin"', async () => {
    // Just verify it does not throw. The domain mismatch with Sepolia
    // testnet would otherwise produce an unrecoverable signature on
    // the mainnet contract; we only test sign-and-not-throw here
    // because the recovery test would need a separate domain.
    const mainnetReq: PaymentRequirement = {
      ...SAMPLE_REQUIREMENT,
      network: 'eip155:8453',
      asset: '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913',
      extra: { name: 'USD Coin', version: '2' },
    };
    const auth = buildAuthorization(TEST_ACCOUNT.address, mainnetReq);
    await expect(signEip3009(TEST_ACCOUNT, mainnetReq, auth)).resolves.toMatch(
      /^0x[0-9a-f]{130}$/,
    );
  });

  it('rejects unparseable network strings up front', async () => {
    const cases = [
      'eip155:NOT_A_NUMBER',
      'eip155',
      'eip155:8453:extra-segment',
      'solana:mainnet',
      '',
    ];
    const auth = buildAuthorization(TEST_ACCOUNT.address, SAMPLE_REQUIREMENT);
    for (const network of cases) {
      const bad: PaymentRequirement = { ...SAMPLE_REQUIREMENT, network };
      await expect(signEip3009(TEST_ACCOUNT, bad, auth)).rejects.toThrow(PaymentError);
    }
  });

  it('hard-fails when server-advertised asset disagrees with the SDK allowlist for a known chain', async () => {
    // Attacker scenario: server says "this is Base mainnet" (true) but
    // points the asset at an arbitrary contract under their control.
    // SDK must refuse — signing here would either burn the nonce or
    // (worse) authorize a transfer against an unrelated contract.
    const malicious: PaymentRequirement = {
      ...SAMPLE_REQUIREMENT,
      network: 'eip155:8453',
      // Random non-USDC contract address
      asset: '0xdeadbeefcafebabe1234567890abcdef12345678',
      extra: { name: 'USD Coin', version: '2' },
    };
    const auth = buildAuthorization(TEST_ACCOUNT.address, malicious);
    await expect(signEip3009(TEST_ACCOUNT, malicious, auth))
      .rejects.toThrow(PaymentError);
    await expect(signEip3009(TEST_ACCOUNT, malicious, auth))
      .rejects.toThrow(/asset_mismatch/);
  });

  it('hard-fails on a known chain when server lies about extra.name (mainnet says USDC)', async () => {
    // The SDK overrides server-supplied name/version with the
    // allowlist entry for known chains, so we cannot test that path
    // by inspecting the signature output (the SDK ignores the bad
    // value). What we CAN verify is that the asset-allowlist check
    // still triggers on any deviation from the canonical contract.
    // This test pairs with the previous one to lock in: known chain
    // = strict allowlist, no chance for the server to substitute.
    const liedAbout: PaymentRequirement = {
      ...SAMPLE_REQUIREMENT,
      network: 'eip155:8453',
      // Wrong contract, plausible but wrong name
      asset: '0x036CbD53842c5426634e7929541eC2318f3dCF7e', // Sepolia USDC on a mainnet 402
      extra: { name: 'USDC', version: '2' },
    };
    const auth = buildAuthorization(TEST_ACCOUNT.address, liedAbout);
    await expect(signEip3009(TEST_ACCOUNT, liedAbout, auth))
      .rejects.toThrow(/asset_mismatch/);
  });

  it('signs successfully on an unknown chain with a console.warn fallback', async () => {
    // Forward-compat: the SDK should still sign for chains it does
    // not know about, but should warn loudly. The test captures
    // console.warn to verify the warning fires.
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const unknownChain: PaymentRequirement = {
      ...SAMPLE_REQUIREMENT,
      network: 'eip155:42161', // Arbitrum one — not in allowlist
      asset: '0xff970a61a04b1ca14834a43f5de4533ebddb5cc8',
      extra: { name: 'USD Coin', version: '1' },
    };
    const auth = buildAuthorization(TEST_ACCOUNT.address, unknownChain);
    const sig = await signEip3009(TEST_ACCOUNT, unknownChain, auth);
    expect(sig).toMatch(/^0x[0-9a-f]{130}$/);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('chain 42161'),
    );
    warnSpy.mockRestore();
  });
});

describe('buildPaymentSignatureHeader', () => {
  it('produces base64-decodable JSON with the x402 v2 envelope', () => {
    const auth = buildAuthorization(TEST_ACCOUNT.address, SAMPLE_REQUIREMENT);
    const sig = ('0x' + 'ab'.repeat(65)) as `0x${string}`;
    const header = buildPaymentSignatureHeader(
      'https://api.koreafilings.com/v1/disclosures/summary?rcptNo=20260424900874',
      SAMPLE_REQUIREMENT,
      auth,
      sig,
    );
    // Should be base64-decodable into a parseable JSON object
    const decoded = JSON.parse(Buffer.from(header, 'base64').toString('utf-8'));
    expect(decoded.x402Version).toBe(X402_VERSION);
    expect(decoded.payload.signature).toBe(sig);
    expect(decoded.resource.url).toContain('rcptNo=20260424900874');
    expect(decoded.payload.authorization.value).toBe(SAMPLE_REQUIREMENT.amount);
    // bigint serialised as string — the on-chain contract takes uint256
    expect(typeof decoded.payload.authorization.validAfter).toBe('string');
  });
});

describe('decodeSettlementHeader', () => {
  it('decodes a valid base64 PAYMENT-RESPONSE payload', () => {
    const proof = {
      success: true,
      transaction: '0xdeadbeef',
      network: 'eip155:8453',
      payer: '0xabc',
    };
    const encoded = Buffer.from(JSON.stringify(proof)).toString('base64');
    expect(decodeSettlementHeader(encoded)).toEqual(proof);
  });

  it('returns null on null/empty/garbage input', () => {
    expect(decodeSettlementHeader(null)).toBeNull();
    expect(decodeSettlementHeader('')).toBeNull();
    expect(decodeSettlementHeader('not-base64-or-json')).toBeNull();
  });
});
