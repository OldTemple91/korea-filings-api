/**
 * Public client for koreafilings.com.
 *
 * The 402 → sign → retry flow is hidden behind `findCompany`,
 * `getRecentFilings`, etc. — the same surface the Python SDK exposes.
 * Settlement proofs from the `PAYMENT-RESPONSE` header (or the
 * legacy `X-PAYMENT-RESPONSE` alias) are attached to the client via
 * `lastSettlement`.
 */

import type { Hex } from 'viem';
import { privateKeyToAccount, type LocalAccount } from 'viem/accounts';
import { ApiError, ConfigurationError, PaymentError } from './errors.js';
import {
  buildAuthorization,
  buildPaymentSignatureHeader,
  decodeSettlementHeader,
  selectRequirement,
  signEip3009,
} from './payment.js';
import type {
  Company,
  PaymentRequirement,
  Pricing,
  RecentFiling,
  SettlementProof,
  Summary,
} from './models.js';

export interface ClientOptions {
  /**
   * 0x-prefixed 32-byte hex string. This key signs the EIP-3009
   * authorizations that move USDC from your wallet to the API's
   * recipient wallet. **Keep this secret.** The SDK never transmits
   * it — signing happens locally, and only the signature goes on
   * the wire.
   */
  privateKey: Hex;
  /**
   * Short alias used only for sanity checks. The authoritative
   * network comes from the 402 response the server sends; if it
   * disagrees with `network`, the call throws `PaymentError` before
   * signing.
   *
   * @default "base-sepolia"
   */
  network?: NetworkAlias;
  /**
   * Override for self-hosted deployments or local development.
   * @default "https://api.koreafilings.com"
   */
  baseUrl?: string;
  /**
   * Per-request timeout in milliseconds. Cold disclosure summaries
   * involve an LLM call and can take ~10s on the server, so the
   * default is generous.
   * @default 60000
   */
  timeoutMs?: number;
}

export type NetworkAlias = 'base-sepolia' | 'base' | 'base-mainnet';

const NETWORK_ALIASES: Record<NetworkAlias, string> = {
  'base-sepolia': 'eip155:84532',
  base: 'eip155:8453',
  'base-mainnet': 'eip155:8453',
};

const DEFAULT_BASE_URL = 'https://api.koreafilings.com';
const DEFAULT_TIMEOUT_MS = 60_000;

/**
 * Server-side `@Pattern` rules from {@code DisclosuresController.java}.
 * Validating client-side as well keeps malformed inputs from costing
 * a nonce slot — the server would reject after the SDK has already
 * signed and submitted an EIP-3009 authorization, wasting one of the
 * 60-second time-limited validity windows on a wallet.
 */
const RCPT_NO_PATTERN = /^\d{14}$/;
const TICKER_PATTERN = /^[0-9A-Z]{6,7}$/;

/**
 * Blocking HTTP client for the koreafilings paid API. Construct once
 * and reuse — every paid call shares the same wallet, base URL, and
 * settlement-proof slot.
 */
export class KoreaFilings {
  private readonly account: LocalAccount;
  private readonly expectedChain: string;
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private _lastSettlement: SettlementProof | null = null;
  private _lastSettlementError: string | null = null;

  constructor(opts: ClientOptions) {
    if (!opts.privateKey || !opts.privateKey.startsWith('0x') || opts.privateKey.length !== 66) {
      throw new ConfigurationError(
        'privateKey must be a 0x-prefixed 32-byte hex string (66 chars)',
      );
    }
    const network: NetworkAlias = opts.network ?? 'base-sepolia';
    if (!(network in NETWORK_ALIASES)) {
      throw new ConfigurationError(
        `unknown network "${network}"; expected one of ${Object.keys(NETWORK_ALIASES).join(', ')}`,
      );
    }
    this.account = privateKeyToAccount(opts.privateKey);
    this.expectedChain = NETWORK_ALIASES[network];
    // Strip ANY number of trailing slashes — a user passing
    // `https://api.koreafilings.com//` would otherwise produce
    // `//v1/...` URLs that some HTTP clients silently rewrite and
    // that proxies / WAFs can reject.
    this.baseUrl = (opts.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '');
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  /** Payer wallet address derived from the configured private key. */
  get address(): Hex {
    return this.account.address;
  }

  /** Settlement proof from the most recent successful paid call, or `null`. */
  get lastSettlement(): SettlementProof | null {
    return this._lastSettlement;
  }

  /**
   * Diagnostic flag for the case where a paid call succeeded
   * (HTTP 200, content delivered, money moved) but the
   * `PAYMENT-RESPONSE` header was missing, malformed, or oversized.
   * `null` means either no paid call has run or the last paid call
   * had a clean settlement proof; a non-null string is the reason
   * the SDK refused to parse it (`'oversize'`, `'not_object'`,
   * `'missing_success'`, `'malformed_json'`, `'header_absent'`).
   *
   * <p>Without this field, an agent inspecting `lastSettlement` on
   * a 200 response cannot distinguish (a) free-tier response,
   * (b) paid call where the server omitted the header,
   * (c) paid call where the server sent a malformed/oversized
   * header. All three look identical (`null`); the user has paid in
   * case (c) and lost the on-chain receipt unless they can
   * reconcile from log / basescan / `payment_log`.
   */
  get lastSettlementError(): string | null {
    return this._lastSettlementError;
  }

  // ------------------------------------------------------------------
  // Free discovery
  // ------------------------------------------------------------------

  /** Fetch the public pricing descriptor. No payment required. */
  async getPricing(): Promise<Pricing> {
    return this.getJson<Pricing>('/v1/pricing');
  }

  /**
   * Search the KRX listed-company directory by name (Korean or
   * English) or ticker. Free, fuzzy via pg_trgm.
   *
   * Use this as the first step in an agent flow when you have a
   * company name but not the ticker. Pass the resulting `ticker` to
   * `getRecentFilings` for paid AI summaries.
   */
  async findCompany(query: string, limit = 20): Promise<Company[]> {
    const params = new URLSearchParams({
      q: query,
      limit: clamp(limit, 1, 50).toString(),
    });
    const body = await this.getJson<{ matches?: Company[] }>(
      `/v1/companies?${params.toString()}`,
    );
    return body.matches ?? [];
  }

  /** Fetch a single company by ticker, or `null` if unknown. Free. */
  async getCompany(ticker: string): Promise<Company | null> {
    const resp = await this.fetch(`${this.baseUrl}/v1/companies/${encodeURIComponent(ticker)}`);
    if (resp.status === 404) return null;
    if (!resp.ok) throw new ApiError(resp.status, await safeJson(resp));
    return (await resp.json()) as Company;
  }

  /**
   * Browse recent DART filings across every listed company. Free,
   * metadata only. Use this to discover what is happening today,
   * then call `getRecentFilings` or `getSummary` to pay for the
   * summaries you actually want.
   */
  async listRecentFilings(opts: { limit?: number; sinceHours?: number } = {}): Promise<RecentFiling[]> {
    const params = new URLSearchParams({
      limit: clamp(opts.limit ?? 20, 1, 100).toString(),
      since_hours: clamp(opts.sinceHours ?? 24, 1, 168).toString(),
    });
    const body = await this.getJson<{ filings?: RecentFiling[] }>(
      `/v1/disclosures/recent?${params.toString()}`,
    );
    return body.filings ?? [];
  }

  // ------------------------------------------------------------------
  // Paid endpoints
  // ------------------------------------------------------------------

  /**
   * Fetch the AI summary for a DART receipt number, paying the
   * required USDC. Cost is fixed at 0.005 USDC per summary
   * regardless of cache state — the cache improves server margin,
   * not caller price.
   *
   * @throws {ConfigurationError} if `rcptNo` is not 14 digits — the
   *   server would reject with 400 anyway, but only AFTER the SDK
   *   has already signed a fresh EIP-3009 authorization, burning
   *   one nonce slot. Validate up front to keep nonce throughput.
   * @throws {ApiError} for HTTP failures that aren't payment prompts.
   * @throws {PaymentError} if signing/settlement is rejected.
   */
  async getSummary(rcptNo: string): Promise<Summary> {
    if (!RCPT_NO_PATTERN.test(rcptNo)) {
      throw new ConfigurationError(
        `rcptNo must be exactly 14 digits, got ${JSON.stringify(rcptNo)}`,
      );
    }
    const params = new URLSearchParams({ rcptNo });
    const body = await this.paidGet(`/v1/disclosures/summary?${params.toString()}`);
    return body as Summary;
  }

  /**
   * Fetch up to `limit` AI summaries for one Korean ticker. Costs
   * `0.005 × limit` USDC, settled in a single x402 payment.
   *
   * Returns up to `limit` summaries, newest first; if the company
   * has fewer recent filings than requested, the response is shorter
   * and the agent has overpaid for the missing slots — pre-filter
   * with `listRecentFilings` if budget is tight. `limit` is capped
   * at 50 server-side.
   *
   * @throws {ConfigurationError} if `ticker` is not 6–7 alphanumeric
   *   characters (KRX SPAC tickers can include letters; standard
   *   listed tickers are 6 digits). Same up-front-validation
   *   rationale as `getSummary`.
   * @throws {ApiError} / {PaymentError} as for `getSummary`.
   */
  async getRecentFilings(ticker: string, limit = 5): Promise<Summary[]> {
    if (!TICKER_PATTERN.test(ticker)) {
      throw new ConfigurationError(
        `ticker must be 6-7 alphanumeric characters (e.g. "005930"), got ${JSON.stringify(ticker)}`,
      );
    }
    const params = new URLSearchParams({
      ticker,
      limit: clamp(limit, 1, 50).toString(),
    });
    const body = (await this.paidGet(`/v1/disclosures/by-ticker?${params.toString()}`)) as {
      summaries?: Summary[];
    };
    return body.summaries ?? [];
  }

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  /**
   * Issue a paid GET, signing the EIP-3009 authorization on demand.
   *
   * Centralises the 402 → sign → retry flow so every paid endpoint
   * shares the same error handling, network mismatch check, and
   * settlement-proof attachment.
   */
  private async paidGet(path: string): Promise<unknown> {
    const url = `${this.baseUrl}${path}`;
    const unpaid = await this.fetch(url);
    if (unpaid.status === 200) {
      // Server-issued free response — preserved for forward
      // compatibility with future tiers.
      this._lastSettlement = null;
      return unpaid.json();
    }
    if (unpaid.status !== 402) {
      throw new ApiError(unpaid.status, await safeJson(unpaid));
    }

    const body = (await safeJson(unpaid)) as { accepts?: PaymentRequirement[] } | null;
    const requirement = selectRequirement(body?.accepts ?? []);

    if (requirement.network !== this.expectedChain) {
      throw new PaymentError(
        'network_mismatch',
        {
          expected: this.expectedChain,
          observed: requirement.network,
          recommendation: 'reconfigure_client_or_refuse_server',
          rationale:
            'The 402 challenge advertised a chain different from the one the ' +
            'client was constructed with. Either reconfigure the client to ' +
            'match the server, or treat the discrepancy as hostile and stop.',
        },
      );
    }

    const authorization = buildAuthorization(this.account.address, requirement);
    const signature = await signEip3009(this.account, requirement, authorization);
    const headerValue = buildPaymentSignatureHeader(url, requirement, authorization, signature);

    // x402 v2 transport spec: PAYMENT-SIGNATURE on request,
    // PAYMENT-RESPONSE on settlement. The server still accepts
    // X-PAYMENT for older clients, but new SDK installs always
    // send the v2 name.
    const paid = await this.fetch(url, {
      headers: { 'PAYMENT-SIGNATURE': headerValue },
    });

    const settlementHeader =
      paid.headers.get('PAYMENT-RESPONSE') ?? paid.headers.get('X-PAYMENT-RESPONSE');
    const settlementRaw = decodeSettlementHeader(settlementHeader) as
      | (SettlementProof & { errorReason?: string })
      | null;

    if (paid.status === 402) {
      // x402 v2 settle-failure shape: HTTP 402 + failure
      // SettlementResponse in PAYMENT-RESPONSE + empty body.
      if (settlementRaw && settlementRaw.success === false) {
        throw new PaymentError(settlementRaw.errorReason ?? 'settle_failed', settlementRaw);
      }
      const rejection = (await safeJson(paid)) as { error?: string } | null;
      throw new PaymentError(rejection?.error ?? 'payment_rejected', rejection);
    }
    if (!paid.ok) {
      throw new ApiError(paid.status, await safeJson(paid));
    }
    if (settlementRaw) {
      this._lastSettlement = settlementRaw;
      this._lastSettlementError = null;
    } else {
      // Settled response (HTTP 200, paid call retried) but no
      // parseable settlement proof. Surface the reason so an agent
      // looking at `lastSettlement === null` after a 200 can tell
      // "no header at all" apart from "header was rejected by the
      // 16 KB cap / shape guard". The on-chain payment is non-
      // refundable in either case — the proof is recoverable from
      // the server's payment_log if needed.
      this._lastSettlement = null;
      this._lastSettlementError = settlementHeader
                ? 'malformed_or_oversize'
                : 'header_absent';
      // eslint-disable-next-line no-console
      console.warn(
        `x402: paid 200 returned without a parseable settlement proof ` +
          `(${this._lastSettlementError}). The on-chain transfer happened — ` +
          `recover the tx hash via the server's payment_log if reconciliation matters.`,
      );
    }
    return paid.json();
  }

  private async getJson<T>(path: string): Promise<T> {
    const resp = await this.fetch(`${this.baseUrl}${path}`);
    if (!resp.ok) throw new ApiError(resp.status, await safeJson(resp));
    return (await resp.json()) as T;
  }

  /**
   * Wraps `globalThis.fetch` with an AbortController-backed timeout.
   * Native fetch is available in Node 18+ and every modern browser,
   * so no `node-fetch` dependency is needed.
   */
  private async fetch(url: string, init: RequestInit = {}): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

async function safeJson(resp: Response): Promise<unknown> {
  try {
    return await resp.json();
  } catch {
    return null;
  }
}
