# koreafilings (TypeScript SDK)

TypeScript SDK for [koreafilings.com](https://koreafilings.com) — pay
per call in USDC over the [x402](https://www.x402.org/) protocol on
Base for AI-summarized Korean DART (전자공시) corporate disclosures.

```bash
npm install koreafilings
```

```ts
import { KoreaFilings } from 'koreafilings';

const client = new KoreaFilings({
  privateKey: process.env.PAYER_PRIVATE_KEY as `0x${string}`,
  network: 'base', // or 'base-sepolia' for testnet
});

// 1. Free — Korean / English company name → six-digit KRX ticker
const matches = await client.findCompany('Samsung Electronics');
const ticker = matches[0]!.ticker; // "005930"

// 2. Paid — 0.005 × limit USDC, settled via x402 in one round-trip
const filings = await client.getRecentFilings(ticker, 5);
for (const f of filings) {
  console.log(`[${f.importanceScore}/10] ${f.eventType}: ${f.summaryEn}`);
}
console.log('paid:', client.lastSettlement?.transaction);
```

## Why this exists

Most x402-paid HTTP APIs in the wild are TypeScript-first because that
is where the agent ecosystem lives — LangChain.js, Vercel AI SDK,
Mastra, Anthropic Agent SDK, Cloudflare Workers, browser-side agents.
The `koreafilings` Python SDK is mature, but Python is not where the
biggest agent population builds. This package brings the same surface
to TS so a JS/TS agent can complete the 402 → sign → retry loop with
one `npm install`.

## What you get

- **Free endpoints** — `findCompany`, `getCompany`, `listRecentFilings`,
  `getPricing`. No wallet required for these; useful for browsing and
  for resolving `name → ticker` before paying.
- **Paid endpoints** — `getSummary` (0.005 USDC fixed) and
  `getRecentFilings` (0.005 × limit USDC, declared dynamically in the
  402 response). The SDK detects the 402, builds the EIP-3009
  `TransferWithAuthorization`, signs it locally with your private
  key via [viem](https://viem.sh/), retries with the
  `PAYMENT-SIGNATURE` header, parses the 200 body, and attaches the
  on-chain settlement proof to `client.lastSettlement`.
- **No facilitator coupling** — the SDK never talks to the x402
  facilitator. Verify and settle happen on the server side.
- **Strict TypeScript types** — `noUncheckedIndexedAccess`,
  `noImplicitOverride`, full domain models. ESM-first with CJS export.

## Wallet model

The constructor takes a 0x-prefixed 32-byte hex private key. The SDK
signs locally; the key never leaves the caller's process. The
recommended pattern is a **fresh burner wallet funded only with the
USDC you intend to spend** — same threat model as any merchant
integration. There is no signup, no KYC, no API key.

```ts
const client = new KoreaFilings({
  privateKey: process.env.PAYER_PRIVATE_KEY as `0x${string}`,
  network: 'base',           // CAIP-2 alias; "base" / "base-mainnet" / "base-sepolia"
  baseUrl: 'https://api.koreafilings.com',  // default
  timeoutMs: 60_000,         // default
});
```

If the server's 402 challenge declares a different network than the
client was constructed with, the SDK throws `PaymentError` before
signing — no signed authorization ever leaves the process if the
network does not match.

## Errors

Three classes, all extending `KoreaFilingsError`:

- `ApiError` — non-2xx, non-402 HTTP response. Carries `status` and
  the parsed JSON body.
- `PaymentError` — payment flow itself failed (network mismatch,
  empty `accepts[]`, signature rejected, settle failed). Carries
  `reason` and any settlement-response detail.
- `ConfigurationError` — only thrown at construction time when
  `privateKey` or `network` is malformed.

```ts
import { KoreaFilings, ApiError, PaymentError } from 'koreafilings';

try {
  const filings = await client.getRecentFilings('005930', 5);
} catch (e) {
  if (e instanceof PaymentError) {
    console.error('payment refused:', e.reason, e.detail);
  } else if (e instanceof ApiError) {
    console.error('api error:', e.status, e.body);
  } else {
    throw e;
  }
}
```

## Discovery

The same pricing / paid-endpoint catalog is also exposed at:

- [`/.well-known/x402`](https://api.koreafilings.com/.well-known/x402) — x402 service descriptor
- [`/.well-known/agent.json`](https://api.koreafilings.com/.well-known/agent.json) — Agent Web Protocol 0.2 manifest
- [`/llms.txt`](https://api.koreafilings.com/llms.txt) — plain-English overview
- [`/v1/pricing`](https://api.koreafilings.com/v1/pricing) — machine-readable pricing descriptor

A cold-start agent can read any of these without authentication and
plan its full free → paid call sequence from one document.

## Dev

```bash
cd sdk/typescript
npm install
npm run typecheck
npm test         # vitest
npm run build    # tsup → dist/{esm,cjs,d.ts}
```

## License

[MIT](LICENSE).
