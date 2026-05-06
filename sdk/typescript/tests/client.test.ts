import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { KoreaFilings } from '../src/client.js';
import { ApiError, ConfigurationError, PaymentError } from '../src/errors.js';

const TEST_KEY = '0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80';

const SAMPLE_REQUIREMENT = {
  scheme: 'exact',
  network: 'eip155:84532',
  amount: '5000',
  asset: '0x036CbD53842c5426634e7929541eC2318f3dCF7e',
  payTo: '0x8467Be164C75824246CFd0fCa8E7F7009fB8f720',
  maxTimeoutSeconds: 60,
  description: 'AI summary of a Korean DART disclosure',
  extra: { name: 'USDC', version: '2' },
};

const SAMPLE_SUMMARY = {
  rcptNo: '20260424900874',
  summaryEn: 'Stock trading suspended due to consolidation.',
  importanceScore: 9,
  eventType: 'SINGLE_STOCK_TRADING_SUSPENSION',
  sectorTags: ['Capital Goods'],
  tickerTags: ['095440'],
  actionableFor: ['traders'],
  generatedAt: '2026-04-24T08:47:51Z',
};

describe('KoreaFilings constructor', () => {
  it('rejects malformed private keys', () => {
    expect(() => new KoreaFilings({ privateKey: 'not-a-key' as `0x${string}` })).toThrow(
      ConfigurationError,
    );
    expect(
      () => new KoreaFilings({ privateKey: ('0x' + 'a'.repeat(60)) as `0x${string}` }),
    ).toThrow(ConfigurationError);
  });

  it('rejects unknown network aliases', () => {
    expect(
      () =>
        new KoreaFilings({
          privateKey: TEST_KEY,
          // @ts-expect-error testing runtime guard
          network: 'ethereum-mainnet',
        }),
    ).toThrow(ConfigurationError);
  });

  it('exposes the wallet address derived from the configured private key', () => {
    const c = new KoreaFilings({ privateKey: TEST_KEY });
    expect(c.address).toMatch(/^0x[0-9a-fA-F]{40}$/);
  });

  it('starts with no settlement proof', () => {
    const c = new KoreaFilings({ privateKey: TEST_KEY });
    expect(c.lastSettlement).toBeNull();
  });
});

describe('KoreaFilings free endpoints (mocked fetch)', () => {
  // `vi.spyOn` cannot infer the global fetch's overloaded signature
  // cleanly; the `any` here is contained to the test setup and the
  // call sites cast back to typed values where needed.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let fetchSpy: any;

  beforeEach(() => {
    fetchSpy = vi.spyOn(globalThis, 'fetch');
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  it('findCompany hits /v1/companies?q= and parses matches', async () => {
    fetchSpy.mockResolvedValueOnce(
      mockJson({ matches: [{ ticker: '005930', corpCode: '00126380', nameKr: '삼성전자' }] }),
    );
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base' });
    const matches = await c.findCompany('Samsung');
    expect(matches[0]?.ticker).toBe('005930');
    const calledUrl = fetchSpy.mock.calls[0]?.[0] as string;
    expect(calledUrl).toContain('/v1/companies?');
    expect(calledUrl).toContain('q=Samsung');
  });

  it('findCompany clamps limit to the server cap', async () => {
    fetchSpy.mockResolvedValueOnce(mockJson({ matches: [] }));
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base' });
    await c.findCompany('Samsung', 999);
    expect(fetchSpy.mock.calls[0]?.[0] as string).toContain('limit=50');
  });

  it('getCompany returns null on 404 instead of throwing', async () => {
    fetchSpy.mockResolvedValueOnce(new Response(null, { status: 404 }));
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base' });
    expect(await c.getCompany('999999')).toBeNull();
  });

  it('listRecentFilings clamps both limit and sinceHours', async () => {
    fetchSpy.mockResolvedValueOnce(mockJson({ filings: [] }));
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base' });
    await c.listRecentFilings({ limit: 9999, sinceHours: 9999 });
    const url = fetchSpy.mock.calls[0]?.[0] as string;
    expect(url).toContain('limit=100');
    expect(url).toContain('since_hours=168');
  });

  it('non-2xx responses surface as ApiError', async () => {
    fetchSpy.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'boom' }), {
        status: 503,
        headers: { 'content-type': 'application/json' },
      }),
    );
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base' });
    await expect(c.findCompany('Samsung')).rejects.toThrow(ApiError);
  });
});

describe('KoreaFilings paid 402 → sign → retry flow', () => {
  // `vi.spyOn` cannot infer the global fetch's overloaded signature
  // cleanly; the `any` here is contained to the test setup and the
  // call sites cast back to typed values where needed.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let fetchSpy: any;

  beforeEach(() => {
    fetchSpy = vi.spyOn(globalThis, 'fetch');
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  it('signs the 402 challenge, retries with PAYMENT-SIGNATURE, parses 200', async () => {
    // First call: 402 with the accepts block
    fetchSpy.mockResolvedValueOnce(
      mockJson({ accepts: [SAMPLE_REQUIREMENT] }, 402),
    );
    // Second call (paid): 200 with the summary + settlement header
    const proof = {
      success: true,
      transaction: '0xdeadbeef',
      network: 'eip155:84532',
      payer: '0xabc',
    };
    fetchSpy.mockResolvedValueOnce(
      mockJson(SAMPLE_SUMMARY, 200, {
        'PAYMENT-RESPONSE': Buffer.from(JSON.stringify(proof)).toString('base64'),
      }),
    );

    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base-sepolia' });
    const result = await c.getSummary('20260424900874');
    expect(result.rcptNo).toBe(SAMPLE_SUMMARY.rcptNo);
    expect(c.lastSettlement?.transaction).toBe('0xdeadbeef');

    // Second fetch must carry the PAYMENT-SIGNATURE header
    const secondInit = fetchSpy.mock.calls[1]?.[1] as RequestInit;
    const headers = secondInit.headers as Record<string, string>;
    const sigHeader = headers['PAYMENT-SIGNATURE'];
    expect(sigHeader).toBeTruthy();
    expect(sigHeader!.length).toBeGreaterThan(40);
  });

  it('throws PaymentError when the 402 advertises a different network than configured', async () => {
    // Server says mainnet (eip155:8453) but client is configured for sepolia (eip155:84532).
    fetchSpy.mockResolvedValueOnce(
      mockJson({ accepts: [{ ...SAMPLE_REQUIREMENT, network: 'eip155:8453' }] }, 402),
    );
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base-sepolia' });
    await expect(c.getSummary('20260424900874')).rejects.toThrow(PaymentError);
  });

  it('throws PaymentError on 402 retry with success=false in PAYMENT-RESPONSE', async () => {
    // Settle failure shape: 402 + failure SettlementResponse + empty body
    fetchSpy.mockResolvedValueOnce(mockJson({ accepts: [SAMPLE_REQUIREMENT] }, 402));
    const failure = { success: false, errorReason: 'invalid_payload' };
    fetchSpy.mockResolvedValueOnce(
      new Response(null, {
        status: 402,
        headers: {
          'PAYMENT-RESPONSE': Buffer.from(JSON.stringify(failure)).toString('base64'),
        },
      }),
    );
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base-sepolia' });
    await expect(c.getSummary('20260424900874')).rejects.toThrow(/invalid_payload/);
  });

  it('legacy X-PAYMENT-RESPONSE header is also accepted on the settled response', async () => {
    fetchSpy.mockResolvedValueOnce(mockJson({ accepts: [SAMPLE_REQUIREMENT] }, 402));
    const proof = { success: true, transaction: '0xfeedbeef', network: 'eip155:84532' };
    fetchSpy.mockResolvedValueOnce(
      mockJson(SAMPLE_SUMMARY, 200, {
        // No PAYMENT-RESPONSE; only the legacy alias
        'X-PAYMENT-RESPONSE': Buffer.from(JSON.stringify(proof)).toString('base64'),
      }),
    );
    const c = new KoreaFilings({ privateKey: TEST_KEY, network: 'base-sepolia' });
    await c.getSummary('20260424900874');
    expect(c.lastSettlement?.transaction).toBe('0xfeedbeef');
  });
});

function mockJson(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...extraHeaders },
  });
}
