/**
 * koreafilings — TypeScript SDK for AI-summarized Korean DART
 * disclosures, paid per call in USDC via x402 on Base.
 *
 * Minimal usage:
 *
 *   import { KoreaFilings } from 'koreafilings';
 *
 *   const client = new KoreaFilings({
 *     privateKey: '0x...',
 *     network: 'base',
 *   });
 *
 *   // Free
 *   const matches = await client.findCompany('Samsung Electronics');
 *   const ticker = matches[0].ticker;  // "005930"
 *
 *   // Paid: 0.005 × limit USDC
 *   const filings = await client.getRecentFilings(ticker, 5);
 *   for (const f of filings) {
 *     console.log(`[${f.importanceScore}/10] ${f.eventType}: ${f.summaryEn}`);
 *   }
 *   console.log('paid:', client.lastSettlement?.transaction);
 */

export { KoreaFilings } from './client.js';
export type { ClientOptions, NetworkAlias } from './client.js';
export type {
  Company,
  PaymentRequirement,
  Pricing,
  PricingEndpoint,
  RecentFiling,
  SettlementProof,
  Summary,
} from './models.js';
export {
  ApiError,
  ConfigurationError,
  KoreaFilingsError,
  PaymentError,
} from './errors.js';
