# Korea Filings

[![PyPI](https://img.shields.io/pypi/v/koreafilings.svg?label=koreafilings)](https://pypi.org/project/koreafilings/)
[![PyPI MCP](https://img.shields.io/pypi/v/koreafilings-mcp.svg?label=koreafilings-mcp)](https://pypi.org/project/koreafilings-mcp/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![x402](https://img.shields.io/badge/payments-x402-orange.svg)](https://www.x402.org/)

Machine-ready English summaries of Korean corporate disclosures
(DART · 전자공시), paid per call in USDC over the
[x402](https://www.x402.org/) protocol on Base. Built for AI agents,
quant funds, and research platforms that need programmatic access to
Korean market events without reading Korean PDFs.

**Live**: <https://koreafilings.com> · API at
<https://api.koreafilings.com> · interactive docs at
[`/swagger-ui`](https://api.koreafilings.com/swagger-ui/index.html).

## What it does

Raw DART data is free, but it's in Korean and structured for human
filings clerks, not LLMs. Korea Filings turns every disclosure into a
structured, cached, English-summarised JSON payload — agents resolve
a Korean company by name for free, then fetch a batch of summaries
for that ticker in one paid x402 call. Each summary looks like:

```json
{
  "rcptNo": "20260424900874",
  "summaryEn": "Global SM's stock trading was temporarily suspended on April 24, 2026, due to a change in electronic registration related to a stock consolidation or split.",
  "importanceScore": 10,
  "eventType": "SINGLE_STOCK_TRADING_SUSPENSION",
  "sectorTags": ["Capital Goods"],
  "tickerTags": ["095440"],
  "actionableFor": ["traders", "long_term_investors"],
  "generatedAt": "2026-04-24T08:47:51Z"
}
```

The cache is the moat — the first agent to request a disclosure pays
the LLM cost; every subsequent agent for the same `rcpt_no` hits a
near-zero-cost DB lookup and still pays the same flat 0.005 USDC per
summary. Batch by-ticker calls hit the same cache row-for-row, so a
five-summary call is five cache lookups against one transferred
USDC payment. Margins compound as adoption grows.

## How to use it

Pick whichever surface fits your stack. All three speak the same x402
flow under the hood; the wallet that signs the `PAYMENT-SIGNATURE` header *is*
the identity. No API keys. No signup.

### Python SDK

```bash
pip install koreafilings
```

```python
from koreafilings import Client

with Client(private_key="0x...", network="base") as client:
    # 1. Free name → ticker resolution
    matches = client.find_company("Samsung Electronics")
    ticker = matches[0].ticker  # "005930"

    # 2. Paid batch summary fetch (0.005 × limit USDC)
    filings = client.get_recent_filings(ticker, limit=5)
    for f in filings:
        print(f"[{f.importance_score}/10] {f.event_type}: {f.summary_en}")
    print("paid:", client.last_settlement.tx_hash)
```

### MCP server (Claude Desktop, Cursor, Continue, …)

```bash
uv tool install koreafilings-mcp
```

In your MCP client's config:

```json
{
  "mcpServers": {
    "koreafilings": {
      "command": "uv",
      "args": ["tool", "run", "koreafilings-mcp"],
      "env": {
        "KOREAFILINGS_PRIVATE_KEY": "0x...",
        "KOREAFILINGS_NETWORK": "base"
      }
    }
  }
}
```

Five tools become available — three free for discovery, two paid:

- `find_company(query)` — **free**; trigram fuzzy search of 3,961
  KRX-listed companies by Korean name, English name, or ticker.
- `list_recent_filings(limit)` — **free**; market-wide recent DART
  feed (metadata only — let the agent decide what to pay for).
- `get_pricing()` — **free**; live wallet, network, USDC contract,
  per-endpoint price.
- `get_recent_filings(ticker, limit)` — **paid 0.005 × limit USDC**;
  batch AI summaries for one ticker, with the on-chain settlement
  transaction hash.
- `get_disclosure_summary(rcpt_no)` — **paid 0.005 USDC**; single
  AI summary for a known receipt number.

The natural agent flow is `find_company` → `get_recent_filings`:
one free call to resolve a name to a ticker, one paid call to fetch
summaries for that ticker.

### curl / direct HTTP

```bash
# 1) Resolve a company name to a ticker. Free, no wallet needed.
curl 'https://api.koreafilings.com/v1/companies?q=Samsung+Electronics&limit=1'
#   HTTP/2 200
#   { "matches": [{ "ticker": "005930", "nameKr": "삼성전자",
#                   "nameEn": "SAMSUNG ELECTRONICS CO.,LTD.",
#                   "market": "KOSPI", ... }] }

# 2) Probe the paid endpoint without payment — server tells you the
#    exact USDC amount it wants for `limit=N` summaries.
curl -i 'https://api.koreafilings.com/v1/disclosures/by-ticker?ticker=005930&limit=3'
#   HTTP/2 402
#   payment-required: <base64 PaymentRequired payload, amount = 15000>
#   { "x402Version": 2, "accepts": [{ "scheme": "exact",
#       "amount": "15000", "asset": "USDC", "payTo": "0x8467…",
#       ... }], ... }

# 3) Sign an EIP-3009 TransferWithAuthorization for one of the entries
#    in `accepts`, base64-encode the signed PaymentPayload, and resend
#    with the PAYMENT-SIGNATURE header (x402 v2 transport spec).
#    See testclient/payer.py for a ~150-line reference implementation.
curl -H "PAYMENT-SIGNATURE: $SIGNED" \
     'https://api.koreafilings.com/v1/disclosures/by-ticker?ticker=005930&limit=3'
#   HTTP/2 200
#   payment-response: <base64 SettlementResponse with tx hash>
#   {
#     "ticker":     "005930",
#     "chargedFor": 3,         # what the agent paid for (`limit`)
#     "delivered":  3,         # how many summaries were actually returned
#     "count":      3,         # alias of `delivered` for older clients
#     "summaries":  [ { "rcptNo": "...", "summaryEn": "...", ... }, … ]
#   }
# `chargedFor` and `delivered` diverge when a ticker has fewer recent
# filings than `limit` or when one of those filings does not yet have
# an AI summary in cache.
```

The flat 0.005 USDC `/v1/disclosures/summary?rcptNo=…` endpoint is
still there for callers that already have a 14-digit receipt number
— same x402 flow, just `amount = 5000` and a single-summary body.

## Pricing

Per call, in USDC on Base. Free endpoints (`/v1/companies`,
`/v1/companies/{ticker}`, `/v1/disclosures/recent`) carry no payment
challenge so an agent can browse before paying.

| Endpoint | Method | Price (USDC) |
|---|---|---|
| `/v1/disclosures/by-ticker?ticker=…&limit=N` | GET | 0.005 × N |
| `/v1/disclosures/summary?rcptNo=…` | GET | 0.005 |

Per-result pricing on the by-ticker endpoint is declared dynamically
in the 402 challenge — for `limit=N`, the server signs `0.005 × N`
USDC into `accepts[0].amount` so the caller sees the exact charge
before authorising the wallet. The flat-rate single-summary endpoint
stays at 0.005 USDC and is the right shape when a caller already has
a 14-digit receipt number from somewhere else.

The full machine-readable pricing descriptor (current wallet, network,
USDC contract, every paid endpoint) lives at
[`/v1/pricing`](https://api.koreafilings.com/v1/pricing); agent-driven
discovery is at
[`/.well-known/x402`](https://api.koreafilings.com/.well-known/x402).
The same paid-action surface is also exposed in Agent Web Protocol
(AWP) shape at
[`/.well-known/agent.json`](https://api.koreafilings.com/.well-known/agent.json),
and a plain-English overview for AI agents lives at
[`/llms.txt`](https://api.koreafilings.com/llms.txt).

Live on **Base mainnet** via the Coinbase CDP facilitator. The first
on-chain settlement is permanent at
[0x681c995e…](https://basescan.org/tx/0x681c995e149d3ce5765ea8a3b0f921a45352fccefbd9fc9258bf4f6141eafd7c) —
a payer wallet moved 0.005 USDC to the merchant wallet
`0x8467Be164C75824246CFd0fCa8E7F7009fB8f720` in a single
`transferWithAuthorization` call.

## Architecture

Three logical subsystems share one Spring Boot application:

1. **Ingestion** — schedules a 30-second poll against the DART Open
   API, deduplicates by `rcpt_no`, persists raw metadata to Postgres,
   enqueues a summarisation job.
2. **Summarisation** — consumes summarisation jobs, classifies
   complexity, routes to Gemini 2.5 Flash-Lite (with Resilience4j
   rate-limiting + circuit-breaking + retries), persists English
   summary + ticker / sector tags + audit row to `llm_audit`.
3. **Paid API** — Spring MVC controller behind an `X402PaywallInterceptor`.
   Every request: read `PAYMENT-SIGNATURE` (or the legacy `X-PAYMENT`
   alias for 0.2.x clients), verify the signature with the facilitator,
   check Redis for replay, settle on a 200 response, and attach
   `PAYMENT-RESPONSE` carrying the on-chain tx hash via a
   `ResponseBodyAdvice`. If `/settle` throws or rejects, the body is
   rewritten to the x402 v2 settle-failure shape (HTTP 402 with the
   failure SettlementResponse base64-encoded into `PAYMENT-RESPONSE`
   and an empty body) so a facilitator outage cannot leak
   paid data unpaid. The interceptor short-circuits for handler methods
   without `@X402Paywall`, so `/v1/pricing`, `/.well-known/x402`, and
   the OpenAPI document stay unauthenticated.

The 402 challenge follows the
[x402 v2 transport spec](https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md):
the `PAYMENT-REQUIRED` header carries the base64-encoded
`PaymentRequired` payload (with the [`bazaar`](https://github.com/coinbase/x402/blob/main/specs/extensions/bazaar.md)
extension declaring an input/output schema for AI-agent
discoverability), while the body keeps a v1-compatible JSON copy so
older clients keep working.

Stack: Java 21, Spring Boot 3.4, PostgreSQL 16, Redis 7, Docker
Compose, Cloudflare Tunnel, Cloudflare Workers. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for deeper notes.

## Repository layout

```
.
├── src/                  # Spring Boot application source
├── sdk/python/           # `koreafilings` Python SDK (PyPI)
├── mcp/                  # `koreafilings-mcp` MCP server (PyPI)
├── landing/              # Marketing landing page (Cloudflare Workers)
├── testclient/           # Reference Python x402 client (testnet payer)
├── docs/
│   ├── ARCHITECTURE.md   # System design
│   ├── PRD.md            # Product requirements
│   ├── ROADMAP.md        # Six-week launch plan
│   └── STATUS.md         # Operator handoff notes
├── Dockerfile            # Multi-stage prod build (eclipse-temurin:21)
├── docker-compose.yml    # postgres + redis + app + cloudflared
└── build.gradle.kts      # Gradle (Kotlin DSL)
```

## Local development

```bash
git clone https://github.com/OldTemple91/korea-filings-api.git
cd korea-filings-api

cp .env.example .env
# Fill in:
#   POSTGRES_PASSWORD     (any strong password)
#   DART_API_KEY          (free, register at https://opendart.fss.or.kr/)
#   GEMINI_API_KEY        (free tier, https://aistudio.google.com/apikey)
#   X402_RECIPIENT_ADDRESS (your receiving wallet — only the address)

docker compose up -d postgres redis
./gradlew bootRun
```

To exercise a real x402 payment against a local instance, copy
`testclient/.env.testclient.example` to `testclient/.env.testclient`,
fill in a wallet's private key (a fresh burner wallet funded with a
dollar or two of Base mainnet USDC is the safe pattern), and run
`python testclient/payer.py`. For local development against the public
testnet facilitator, point `X402_FACILITATOR_URL` at
`https://www.x402.org/facilitator` and use Base Sepolia parameters in
your `.env`.

## Status

Live on **Base mainnet** with verified on-chain settlement
([first tx](https://basescan.org/tx/0x681c995e149d3ce5765ea8a3b0f921a45352fccefbd9fc9258bf4f6141eafd7c)).
MVP feature set:

- DART real-time ingestion (30-second poll)
- Gemini 2.5 Flash-Lite summarisation with importance scoring + sector / ticker tagging
- x402 v2 paywall with `bazaar` extension for agent-discoverable invocation
- Discovery via `/.well-known/x402`
- OpenAPI 3 spec at [`/v3/api-docs`](https://api.koreafilings.com/v3/api-docs) + interactive Swagger UI
- Python SDK ([`koreafilings` 0.3.1](https://pypi.org/project/koreafilings/)) and MCP server ([`koreafilings-mcp` 0.3.0](https://pypi.org/project/koreafilings-mcp/)) on PyPI
- Free name → ticker resolution (`find_company`) + free recent feed (`list_recent_filings`) so agents can browse before paying
- Per-result paid batch endpoint (`/v1/disclosures/by-ticker?ticker=…&limit=N`) with 0.005 × N USDC declared dynamically in the 402
- Indexed by [x402scan](https://www.x402scan.com)
- Production deploy on a Linux VPS via Cloudflare Tunnel
- Coinbase CDP facilitator (Ed25519 JWT auth) for mainnet settlement

Current limitation: every summary the service produces today is
generated from filing metadata only — title, date, filer, DART flag.
That is enough to surface event type, importance, and ticker / sector
tags ("first-pass screening"), but not enough to extract concrete
numbers like rights-offering size, dilution %, or contract value. The
LLM honestly admits this with phrases like "details are in the filing
body" rather than fabricating figures.

Coming next:

- **v1.2 — deep filing analysis.** Pull the filing body via DART's
  `/document.xml` ZIP endpoint, parse the XBRL templates for the
  six highest-value event types (RIGHTS_OFFERING,
  CONVERTIBLE_BOND_ISSUANCE, DEBT_ISSUANCE, ACQUISITION,
  SUPPLY_CONTRACT_SIGNED, MAJOR_SHAREHOLDER_FILING), and extract
  amounts, dilution %, counterparty, and dates into a structured
  `keyFacts` field. New paid endpoint
  `/v1/disclosures/deep?rcptNo=…` at a higher price tier (~0.020
  USDC) — existing endpoints stay metadata-only at 0.005 USDC so
  callers pick depth at call time. Roadmap detail in
  [`docs/ROADMAP.md`](docs/ROADMAP.md#v12--deep-filing-analysis-planned).
- POST `/v1/disclosures/filter` (sector + event-type query)
- SSE `/v1/disclosures/stream` (real-time push)
- TypeScript SDK
- Korean-language landing page
- Slack / email alerts on settlement

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the full plan.

## Contributing

Issues and PRs are welcome — particularly:

- Ports of the Python SDK to other languages (TypeScript, Go, Rust)
- Additional analytics endpoints (price reaction, comparable filings, …)
- Integrations with non-x402 agent frameworks
- Translation of the landing page into other languages

For substantial changes, please open an issue first describing the
direction so we can sanity-check fit before you build.

## License

[MIT](LICENSE).
