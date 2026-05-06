/**
 * Live payment example — runs against api.koreafilings.com using a
 * funded wallet. Costs `0.005 × limit` USDC per run, so the default
 * `limit=2` settles for 0.01 USDC.
 *
 * Run with:
 *   PAYER_PRIVATE_KEY=0x... \
 *     npx tsx examples/live-payment.ts
 *
 * Use a fresh burner wallet funded only with what you intend to spend.
 * Mainnet vs Sepolia is governed by `KF_NETWORK` (default "base"),
 * matching the asset address advertised in the 402 challenge.
 */

import { KoreaFilings, ApiError, PaymentError } from '../src/index.js';

const privateKey = process.env.PAYER_PRIVATE_KEY as `0x${string}` | undefined;
if (!privateKey) {
  console.error('Set PAYER_PRIVATE_KEY=0x... to run this example.');
  process.exit(1);
}

const client = new KoreaFilings({
  privateKey,
  network: (process.env.KF_NETWORK as 'base' | 'base-sepolia' | undefined) ?? 'base',
});

console.log('payer wallet:', client.address);

try {
  // Free name → ticker resolution
  const matches = await client.findCompany(process.env.KF_QUERY ?? 'Samsung Electronics');
  if (matches.length === 0) {
    console.error('no matches for query');
    process.exit(2);
  }
  const ticker = matches[0]!.ticker;
  console.log(`resolved ${matches[0]!.nameEn ?? matches[0]!.nameKr} → ${ticker}`);

  // Paid batch fetch
  const limit = Number(process.env.KF_LIMIT ?? '2');
  const filings = await client.getRecentFilings(ticker, limit);
  for (const f of filings) {
    console.log(`[${f.importanceScore}/10] ${f.eventType}: ${f.summaryEn}`);
  }

  if (client.lastSettlement) {
    console.log('paid', `${(0.005 * limit).toFixed(3)} USDC`);
    console.log('tx:', client.lastSettlement.transaction);
  }
} catch (e) {
  if (e instanceof PaymentError) {
    console.error('payment refused:', e.reason, e.detail);
  } else if (e instanceof ApiError) {
    console.error('api error:', e.status, e.body);
  } else {
    throw e;
  }
  process.exit(3);
}
