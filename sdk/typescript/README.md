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

A real summary returned from a live Base mainnet paid call against
[Samsung Electronics' 2026-Q1 dividend filing](https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260430800106):

```text
[7/10] DIVIDEND_DECISION: Samsung Electronics decided on a quarterly
  cash dividend of KRW 372 per common share and KRW 372 per preferred
  share, totaling KRW 2,453,315,636,604. The dividend yield is 0.2%
  for common shares and 0.3% for preferred shares. The record date is
  March 31, 2026, with payment scheduled for May 29, 2026.
paid: 0x<base-mainnet-tx-hash>...
```

The summary is built from the filing body itself (fetched lazily via
DART's `/document.xml` ZIP, parsed and capped at 20,000 chars) rather
than the title metadata only — quantitative events surface concrete
amounts, percentages, dates, and counterparty names directly in
`summaryEn`. Routine governance filings fall back to a shorter
title-derived summary; the field is always present and never empty.

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
- `PaymentError` — payment flow itself failed. Carries a stable
  `reason` string and a structured `detail` (object or wire payload)
  that an agent can branch on without parsing the human message.
  See the `reason` table below.
- `ConfigurationError` — only thrown at construction time when
  `privateKey` or `network` is malformed, or when a malformed
  `rcptNo` / `ticker` is passed to `getSummary` / `getRecentFilings`
  (validated client-side BEFORE issuing the unpaid GET, so a bad
  input does not burn a nonce slot).

### `PaymentError.reason` vocabulary

| `reason` | Where it fires | Recoverable? | Recommendation |
|---|---|---|---|
| `empty_accepts` | 402 body has no `accepts[]` entries — server bug | No, terminal | Refuse this server, contact maintainer |
| `invalid_network` | 402's `network` is not the CAIP-2 `eip155:<chainId>` shape | No, terminal | Refuse this server |
| `network_mismatch` | 402 advertises a chain ≠ client's `network` config | Maybe | Reconfigure client to match server, or refuse if hostile. `detail` has `expected` + `observed` |
| `asset_mismatch` | Known chain but server advertises a non-canonical USDC contract | **No** — terminal, hostile | Refuse this server. `detail` has `expected` + `observed` + `recommendation` |
| `payment_rejected` | Facilitator's `/verify` rejected the signature | Sometimes | Check wallet key / clock skew, retry with fresh nonce |
| `settle_failed` | Facilitator's `/settle` rejected after a successful verify | Sometimes | Wait for facilitator to clear, retry with fresh nonce |
| (facilitator-supplied) | A 402 retry returned `success:false` with a custom `errorReason` | Depends | Inspect the message — usually transient |

```ts
import { KoreaFilings, ApiError, PaymentError } from 'koreafilings';

try {
  const filings = await client.getRecentFilings('005930', 5);
} catch (e) {
  if (e instanceof PaymentError) {
    if (e.reason === 'asset_mismatch') {
      // Server advertised a non-canonical USDC contract — never retry, treat as hostile.
      throw new Error('Refusing to use a server that advertises a non-canonical USDC contract');
    } else if (e.reason === 'network_mismatch') {
      console.error('chain mismatch:', e.detail);
    } else {
      console.error('payment refused:', e.reason, e.detail);
    }
  } else if (e instanceof ApiError) {
    console.error('api error:', e.status, e.body);
  } else {
    throw e;
  }
}
```

### `lastSettlement` and `lastSettlementError`

After a paid call returns 200, `client.lastSettlement` carries the
on-chain settlement proof (tx hash, payer, network). It is `null` in
three different cases:

| `lastSettlement` | `lastSettlementError` | Means |
|---|---|---|
| `SettlementProof` object | `null` | Paid call settled cleanly |
| `null` | `null` | Free-tier response — no payment was needed |
| `null` | `'header_absent'` | Paid call (HTTP 200, money moved) but server omitted `PAYMENT-RESPONSE` |
| `null` | `'malformed_or_oversize'` | Paid call but the header was unparseable or > 16 KB (the SDK rejected to defend against DoS) |

Use `lastSettlementError` to disambiguate. In the `header_absent` /
`malformed_or_oversize` cases the on-chain transfer still happened —
the proof is recoverable from the server's `payment_log` table if
reconciliation matters; the SDK itself logs a `console.warn` so the
side-channel is visible.

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

## Cross-language naming differences

The Python and TypeScript SDKs share the same surface, but each
follows its host language's naming convention. If you are porting
code line-by-line between them, watch for:

| Concept | Python | TypeScript |
|---|---|---|
| Constructor | `Client(private_key=, network=)` | `new KoreaFilings({ privateKey, network })` |
| Method names | `find_company`, `list_recent_filings`, `get_recent_filings`, `get_summary`, `get_pricing` | `findCompany`, `listRecentFilings`, `getRecentFilings`, `getSummary`, `getPricing` |
| HTTP error status | `ApiError.status_code` | `ApiError.status` |
| Settlement tx hash | `client.last_settlement.tx_hash` | `client.lastSettlement?.transaction` |
| Settlement error reason | `last_settlement.error_reason` | `lastSettlement?.errorReason` |
| `listRecentFilings` arguments | kwargs: `limit=20, since_hours=24` | options object: `{ limit: 20, sinceHours: 24 }` |

Defaults, server caps (1–50 / 1–100 / 1–168), payment headers,
network aliases, EIP-712 domain handling, and 402 → sign → retry
behaviour are byte-identical between the two SDKs. Only the
language-idiomatic surface differs.

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
