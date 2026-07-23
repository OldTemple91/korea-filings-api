/**
 * TypeScript shapes for every API response. Field names stay in the
 * server's camelCase form because that's what the wire returns — we
 * do not re-name on the way in. Same convention as the Python SDK's
 * Pydantic models, just expressed as TS interfaces.
 */

/**
 * AI-generated English summary of a single DART disclosure. The
 * payload the paid endpoints return after a successful x402
 * settlement.
 *
 * `importanceScore` is 1–10, where 10 is an event like M&A or
 * insolvency and 1 is a routine administrative filing.
 */
export interface Summary {
  rcptNo: string;
  /** Korean company name as filed with DART (server round-18). */
  corpName?: string;
  /** KRX-registered English company name. Absent for unlisted issuers. */
  corpNameEn?: string;
  /** Canonical DART form name, Korean (server round-18). */
  reportNm?: string;
  /** English filing-type label derived from `eventType`, e.g. "Merger Decision". */
  reportNmEn?: string;
  summaryEn: string;
  importanceScore: number;
  eventType: string;
  sectorTags: string[];
  tickerTags: string[];
  actionableFor: string[];
  /** Canonical DART viewer link — audit path to the original filing (round-17a). */
  sourceUrl?: string;
  /** "HIGH" | "LOW" — whether this filing class normally carries an extractable number (round-17a). */
  numericExpectation?: string;
  /** ISO-8601 timestamp string, e.g. "2026-04-24T08:47:51Z". */
  generatedAt: string;
}

/**
 * On-chain settlement proof returned in the `PAYMENT-RESPONSE`
 * header (or the legacy `X-PAYMENT-RESPONSE` alias). `transaction`
 * is the Base transaction hash, lookupable on basescan.org.
 *
 * `errorReason` is populated only when `success` is `false` — the
 * facilitator only reaches this path on partial settlements; hard
 * rejects surface as `PaymentError` before a proof is emitted.
 */
export interface SettlementProof {
  success: boolean;
  /** Base transaction hash. Optional because some failure shapes omit it. */
  transaction?: string;
  /** CAIP-2 network id, e.g. "eip155:8453" for Base mainnet. */
  network?: string;
  /** Payer wallet address. */
  payer?: string;
  errorReason?: string;
}

/** A single endpoint entry in the `/v1/pricing` descriptor. */
export interface PricingEndpoint {
  method: string;
  path: string;
  priceUsdc: string;
  description: string;
  pricingMode?: 'fixed' | 'per_result';
  requiredParams?: Array<{
    name: string;
    in: string;
    type: string;
    required: boolean;
    description: string;
    example?: string;
  }>;
  exampleCall?: string;
}

/**
 * Machine-readable pricing descriptor from `GET /v1/pricing`. Free
 * call; useful for dynamically discovering paid endpoints without
 * hardcoding paths.
 */
export interface Pricing {
  x402Network: string;
  x402Asset: string;
  x402Recipient: string;
  endpoints: PricingEndpoint[];
}

/**
 * A Korean listed company from the KRX directory. Returned by the
 * free `findCompany` and `getCompany` methods.
 *
 * `ticker` is the six-digit KRX code; `corpCode` is DART's 8-digit
 * internal id, rarely needed by an agent but exposed for advanced
 * use cases.
 */
export interface Company {
  ticker: string;
  corpCode: string;
  nameKr: string;
  nameEn?: string;
  market?: string;
  /** Only present on `findCompany` responses. */
  isExactMatch?: boolean;
}

/**
 * Lightweight metadata for a recent DART filing — no AI summary.
 * Returned by the free `listRecentFilings` method.
 *
 * Use `rcptNo` to fetch a single summary via `getSummary`, or the
 * `ticker` to fetch a batch via `getRecentFilings`.
 */
export interface RecentFiling {
  rcptNo: string;
  ticker?: string;
  corpName: string;
  /** KRX-registered English company name (server round-18). Absent for unlisted issuers. */
  corpNameEn?: string;
  reportNm: string;
  /** English filing-type label, e.g. "Trading Suspension" (server round-18). */
  reportNmEn?: string;
  /** ISO date string, e.g. "2026-04-24". */
  rceptDt: string;
  /** AI enrichment — present only when the summary is already cached server-side (round-15b). */
  importanceScore?: number;
  eventType?: string;
  sectorTags?: string[];
  tickerTags?: string[];
  actionableFor?: string[];
  /** Canonical DART viewer link (round-17a). */
  sourceUrl?: string;
  /** "HIGH" | "LOW" pre-purchase numeric-content signal (round-17a). */
  numericExpectation?: string;
}

/**
 * Internal — payment requirement parsed from the 402 challenge's
 * `accepts[]` array. Not part of the public SDK surface.
 *
 * @internal
 */
export interface PaymentRequirement {
  scheme: string;
  /** CAIP-2 network identifier, e.g. "eip155:8453". */
  network: string;
  /** Amount in the asset's smallest unit (USDC has 6 decimals). */
  amount: string;
  /** Asset contract address. */
  asset: string;
  /** Merchant wallet that receives the payment. */
  payTo: string;
  maxTimeoutSeconds: number;
  description?: string;
  /** EIP-712 domain extras: `{ name, version }` for `transferWithAuthorization`. */
  extra?: {
    name?: string;
    version?: string;
  };
}
