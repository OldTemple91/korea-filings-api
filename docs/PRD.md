# Product Requirements Document — Korea Filings

## Problem

Korean corporate disclosures are filed through DART (Data Analysis, Retrieval and Transfer System) operated by the Financial Supervisory Service. Roughly **800–1,100 filings are published per weekday (~870 average, ~220,000 per year)** across the ~3,800 KOSPI / KOSDAQ listed companies the service tracks (plus ~80 OTHER and unclassified entries in the DART corpCode dump). The original filings are in Korean, often as PDF attachments to XML metadata, and use domain-specific vocabulary (감자, 유상증자, 자사주 매입, 전환사채, 지주사 전환, 대규모내부거래 등).

Global investors — foreign institutional investors, quant funds, and increasingly AI research agents deployed by these firms — need to react to these filings within minutes. Today they rely on (a) delayed English reporting from Korean financial news outlets, (b) in-house translators, or (c) no coverage at all for non-top-50 tickers. None of these work for an AI agent that must read and act programmatically.

## Solution

A paid API, billed per call in USDC via the x402 protocol, that returns structured English summaries of Korean disclosures with consistent importance scoring and entity tagging. Every disclosure is summarized exactly once (by an LLM) and then served from cache forever, so the marginal cost of the nth request for the same filing approaches zero while the price stays constant.

## Target Users

Primary: Autonomous AI research agents (LangChain, CrewAI, custom agent frameworks) deployed by hedge funds and investment research platforms that cover Asian equities.

Secondary: Independent quants and developers building Korea-focused trading or monitoring tools who want to avoid building a Korean NLP pipeline themselves.

Tertiary: MCP-server users inside Claude Desktop / other MCP clients who want a natural-language interface to Korean market events.

Non-users (explicitly not serving these): Retail Korean-speaking investors — they already have Naver Finance, Kakao Stock, and free DART. Large sell-side institutions with in-house translators — they won't pay a startup for this.

## Functional Requirements

### Ingestion

- The system polls the DART Open API endpoint for the list of new disclosures every 30 seconds. The polling interval is configurable.
- Every new `rcpt_no` (receipt number, DART's unique ID for a filing) is persisted to the `disclosure` table with its metadata: corp_code, corp_name, report_nm, flr_nm, rcept_dt, rm.
- Duplicates (same `rcpt_no` already in the table) are silently ignored.
- After successful persistence, a summarization job is enqueued for the new filing.

### Summarization

- A disclosure's summarization is triggered by the job queue.
- Complexity classification: `ComplexityClassifier` runs a simple keyword rules engine on DART's `report_nm` and tags each filing as SIMPLE, MEDIUM, or COMPLEX. The tag is recorded for observability today; v1 routes every tier to the same model.
- LLM routing (current):
  - All tiers → **Gemini 2.5 Flash-Lite** (single model in production for v1.1).
  - The pipeline is wired through an `LlmClient` interface so a future tier-aware router (e.g. promote COMPLEX to Gemini 2.5 Flash with extended reasoning) can be added without touching the orchestrator.
- The prompt is anchored against a 50-row Korean → English filing-type taxonomy + importance score reference, asking for a JSON object with: `summaryEn` (≤ 400 chars), `importanceScore` (1–10), `sectorTags` (array of GICS-style sector labels), `tickerTags` (array of 6-7 digit alphanumeric codes — SPAC tickers contain letters), `eventType` (one of ~50 canonical values, with `OTHER` as the fallback held under 5%), and `actionableFor` (array of: "traders" | "long_term_investors" | "governance_analysts" | "none").
- The LLM response is validated against a JSON schema. Malformed or transient-failure responses are re-enqueued by `SummaryRetryScheduler` on a separate cadence so paying readers (cache hits) are unaffected by upstream Gemini hiccups.
- The final summary is persisted to `disclosure_summary` and pushed into Redis with key `summary:{rcptNo}` and TTL of 30 days (re-populated on access). The summary is generated **once per `rcpt_no` and cached forever** — every subsequent paid call for that filing hits the DB cache, so margins compound as adoption grows.

> **Honest scope note (v1.1).** Every summary the service produces today is generated from filing **metadata** only — title, date, filer, DART flag. That is enough for event type, importance, and ticker / sector tags ("first-pass screening"), but not enough to extract concrete numbers like rights-offering size, dilution %, or contract value. The LLM honestly admits this with phrases like "details are in the filing body" rather than fabricating figures. v1.2 (planned) introduces a `/v1/disclosures/deep?rcptNo=…` endpoint at ~0.020 USDC that pulls the per-filing XBRL via DART's `/document.xml` and template-extracts the actual numbers into a structured `keyFacts` field. See `docs/ROADMAP.md`.

### Paid API

All paid endpoints sit behind an `X402PaywallInterceptor` + `X402SettlementAdvice` pair. Free endpoints (`/v1/companies`, `/v1/companies/{ticker}`, `/v1/disclosures/recent`, `/v1/pricing`, `/.well-known/x402`) carry no payment challenge so an agent can browse before paying.

Request flow on a paid endpoint:

1. Client sends request without a payment header → server returns 402. Per the [x402 v2 transport spec](https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md), the `PAYMENT-REQUIRED` response header carries the base64-encoded `PaymentRequired` payload (with the `bazaar` extension declaring an input/output schema for AI-agent discoverability), and the body keeps a v1-compatible JSON copy so older clients keep working.
2. For `pricingMode = PER_RESULT` endpoints (e.g. `/v1/disclosures/by-ticker?ticker=…&limit=N`), the server multiplies the unit price by the count query parameter, clamped at `maxCount`, and signs the resulting `0.005 × N` USDC into `accepts[0].amount` so the agent sees the exact charge before authorising.
3. Client signs an EIP-3009 `TransferWithAuthorization` for the declared amount and retries the same request with the base64-encoded payload in `PAYMENT-SIGNATURE`. The legacy `X-PAYMENT` header (v1) is also accepted for the 0.2.x SDK / MCP releases still in the wild.
4. Interceptor first checks Redis `payment_sig:{hash}` with SETNX. If already present, returns 402 "signature reused".
5. Interceptor calls the Coinbase CDP facilitator's `/verify` endpoint (Ed25519 JWT auth) with the payment proof. If invalid, releases the Redis signature and returns 402 with the facilitator error.
6. The controller runs. Business logic has no awareness of payments.
7. On a 2xx response, `X402SettlementAdvice` calls the facilitator's `/settle` endpoint and, on success, writes the settlement proof to the `PAYMENT-RESPONSE` response header (with `X-PAYMENT-RESPONSE` echoed alongside for v1 clients) and appends a row to `payment_log`. **If `/settle` throws or returns `success: false`, the controller body is replaced with a 502 envelope so a facilitator outage cannot leak paid data unpaid.** On non-2xx controller status, settlement is skipped and the signature is released from Redis so the client can retry without paying twice.

### Endpoints

See `README.md` (Pricing section) and the live machine-readable descriptor at [`/v1/pricing`](https://api.koreafilings.com/v1/pricing) for the canonical price list. Schemas live in the OpenAPI spec generated by springdoc, exposed at [`/v3/api-docs`](https://api.koreafilings.com/v3/api-docs) and a Swagger UI at [`/swagger-ui`](https://api.koreafilings.com/swagger-ui/index.html). Agent-driven discovery is at [`/.well-known/x402`](https://api.koreafilings.com/.well-known/x402).

## Non-Functional Requirements

- **Latency**: p95 response time for cached summary reads ≤ 200 ms. Cold (uncached) reads ≤ 8 seconds (dominated by the Gemini call). Free endpoints (`/v1/companies`, `/v1/disclosures/recent`) ≤ 100 ms p95 — pg_trgm fuzzy search across 3,961 rows is sub-50 ms in practice.
- **Availability**: Target 99% monthly uptime for MVP, gated by the underlying VPS provider's SLA + Cloudflare Tunnel. Planned maintenance is acceptable with notice on the status page.
- **Freshness**: New DART disclosures appear in `/v1/disclosures/recent` within 60 seconds of publication (30 s poll interval + 30 s summarisation headroom).
- **Cost per request**: average Gemini cost per unique disclosure ≤ $0.002, amortised to ≤ $0.0003 per call at 10-call reuse. The cache-hit path costs effectively nothing (Postgres lookup + JSON serialisation), so margins compound with adoption. If the per-disclosure floor is ever violated, reconsider the prompt or model.
- **Observability**: Every paid request emits a structured log with `rcptNo`, endpoint, payment amount, facilitator latency, and LLM cost (if applicable). The `payment_log` table is the single source of truth for revenue; a post-launch ops follow-up wires Slack / email alerts on every new row.

## Out of Scope (for v1.1)

- Earnings call transcripts and their summaries (post-MVP, adjacent product).
- Chaebol group relationship graphs (separate product, later).
- Historical backfill beyond 90 days (live-forward only for MVP).
- Websocket push streams (SSE is enough).
- Multi-language summaries (English only for MVP; Japanese and Chinese considered later).
- Korean-to-English translation of the full filing text (summaries only; copyright and cost concerns).
- On-chain credit system for pre-paid calls (pay per call is the model).

### Deferred to v1.2 (planned, not yet built)

- Per-filing **body fetch** via DART's `/document.xml` ZIP endpoint and templated XBRL extraction of concrete numbers (issuance amount, dilution %, contract value, counterparty, dates) for the six highest-value event types: RIGHTS_OFFERING, CONVERTIBLE_BOND_ISSUANCE, DEBT_ISSUANCE, ACQUISITION, SUPPLY_CONTRACT_SIGNED, MAJOR_SHAREHOLDER_FILING.
- New paid endpoint `/v1/disclosures/deep?rcptNo=…` at a higher price tier (~0.020 USDC) and a structured `keyFacts` field on the summary DTO. Existing endpoints stay metadata-only at 0.005 USDC so callers pick depth at call time.
- Build trigger: at least a week of v1.1 traffic showing which filing types agents actually pay for. See `docs/ROADMAP.md#v12--deep-filing-analysis-planned`.

## Success Metrics

The MVP is successful if, by the end of month 3 post-launch:

- At least 10 distinct wallet addresses have made paid calls.
- Monthly recurring revenue ≥ $100 USDC.
- At least one named integration (someone publicly saying "we use Korea Filings in our agent").
- x402scan ranking: top 20 by weekly transaction count in the "data" category.

Stretch: monthly revenue ≥ $500, and one quantitative fund or investment research platform doing > 1,000 calls/day.

## Failure Criteria (When to Pivot)

If by end of month 6:

- Total lifetime revenue < $150
- No repeat users (same wallet calling twice on different days) numbering more than 3
- x402scan traffic dominated by the project's own self-test calls

... then reassess: ship v1.2 deep analysis to lift average revenue per call (higher tier ~0.020 USDC), pivot toward a Korean address-normalisation API (higher call volume but lower margin per call), or pivot toward a Chaebol graph API (higher marginal value per call, different customer).
