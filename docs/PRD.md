# Product Requirements Document — Korea Filings

## Problem

Korean corporate disclosures are filed through DART (Data Analysis, Retrieval and Transfer System) operated by the Financial Supervisory Service. Roughly **800–1,100 filings are published per weekday (~870 average, ~220,000 per year)** across the ~3,800 KOSPI / KOSDAQ listed companies the service tracks (plus ~80 OTHER and unclassified entries in the DART corpCode dump). The original filings are in Korean, often as PDF attachments to XML metadata, and use domain-specific vocabulary (감자, 유상증자, 자사주 매입, 전환사채, 지주사 전환, 대규모내부거래 등).

Korean financial data is the surface; the wedge is the delivery model. Existing options for English Korea coverage — Bloomberg, Refinitiv, FnGuide, Smartkarma — sell monthly subscriptions through procurement channels with corporate logins and bespoke contracts. Autonomous agents and indie agent builders can use none of that: they need pay-per-call, no signup, no API key, and a wire format an LLM can reason about without a human in the loop.

## Solution

An x402-paid HTTP API that returns structured English summaries of Korean disclosures with consistent importance scoring and entity tagging. Every disclosure is summarized exactly once (by an LLM) and then served from cache forever, so the marginal cost of the nth request for the same filing approaches zero while the price stays constant. Wallet authentication, USDC settlement on Base, and machine-readable discovery (`/.well-known/x402`, `/.well-known/agent.json`, `/llms.txt`, `/v1/pricing`) keep the integration loop short enough that an agent can complete its first paid call in single-digit minutes.

## Target Users

**Primary: x402-capable AI agents and the indie devs / small teams who build them.** This is the audience x402 itself was designed for — autonomous agents that prefer pay-per-call, hold their own wallets, run on serverless or self-hosted infrastructure, and pull diverse data sources rather than living inside one vendor's contract. Korean financial data is one such source; the same agent buys research, market data, and tools from many similar APIs. Their willingness-to-pay is small per call but accumulates with usage.

**Secondary: MCP-server users inside Claude Desktop / Cursor / Continue** who want a natural-language interface to Korean market events without setting up their own infrastructure. They install `koreafilings-mcp`, plug a wallet, and ask their assistant in English.

**Tertiary: Crypto-native quant funds doing Asia macro trades.** A small TAM (≈ 5–20 funds globally) but a real fit — they already operate in USDC rails and don't need procurement to use a new data source. Volume per fund is higher than the indie segment.

**Explicitly not serving these segments:**

- **Korean retail investors.** They have Naver Finance, Kakao Stock, and DART itself for free. Korean is their first language; they don't pay for English summaries of Korean filings.
- **Traditional sell-side / institutional asset managers.** They already buy Bloomberg / Refinitiv / FnGuide. Compliance and procurement won't approve a USDC wallet as a billing method, regardless of how cheap per call we make it.
- **Foreign institutional investors with established Asia desks.** Same procurement / subscription expectation as above. The wallet model is a hard constraint, not a price-comparison decision.

This narrowing was rebalanced in 2026-05 after observing that the technical infrastructure was end-to-end live ahead of organic demand — the bottleneck is reach into the x402-capable indie agent ecosystem (TypeScript SDK + HN + Smithery + agent-network presence), not building features for buyers who will never adopt the rail.

## Functional Requirements

### Ingestion

- The system polls the DART Open API endpoint for the list of new disclosures every 30 seconds. The polling interval is configurable.
- Every new `rcpt_no` (receipt number, DART's unique ID for a filing) is persisted to the `disclosure` table with its metadata: corp_code, corp_name, report_nm, flr_nm, rcept_dt, rm.
- Duplicates (same `rcpt_no` already in the table) are silently ignored.
- The body column starts NULL and is populated lazily on the first paid call for the rcpt_no — see Summarization below.

### Summarization

- Summarization runs **lazily** on the first paid call for each `rcpt_no`. The cache miss path inside `DisclosuresController` calls `SummaryService.summarize(rcptNo)` synchronously, with a Redis SETNX single-flight lock keyed `summary_inflight:{rcptNo}` ensuring exactly one LLM call globally even under concurrent paid requests. The legacy eager queue (`summary_job_queue` + background consumer + retry scheduler) remains in code for offline backfill but is disabled by default.
- Complexity classification: `ComplexityClassifier` runs a simple keyword rules engine on DART's `report_nm` and tags each filing as SIMPLE, MEDIUM, or COMPLEX. The tag is recorded for observability today; v1.1 routes every tier to the same model.
- **Body fetch**: when `disclosure.body IS NULL`, `SummaryService` pulls the per-filing ZIP via `DartClient.fetchDocument(rcptNo)` (DART `/api/document.xml`, separate `dart-document` Resilience4j instance with breaker / retry / 30 rpm rate limiter), unzips and strips markup via `DartDocumentParser` (jsoup, 20,000 char cap), and persists the parsed text in a short `REQUIRES_NEW` transaction so a later LLM failure doesn't erase the cached body. Body-fetch failures (404 = filing not yet finalised, open breaker, parse error) degrade gracefully to title-only summarisation.
- LLM routing (current):
  - All tiers → **Gemini 2.5 Flash-Lite** (single model in production for v1.1).
  - The pipeline is wired through an `LlmClient` interface so a future tier-aware router (e.g. promote COMPLEX to Gemini 2.5 Flash with extended reasoning) can be added without touching the orchestrator.
- The prompt is anchored against a 50-row Korean → English filing-type taxonomy + importance score reference, asking for a JSON object with: `summaryEn` (≤ 600 chars; the model is instructed to lead with concrete numbers from the body when available), `importanceScore` (1–10), `sectorTags` (array of GICS-style sector labels), `tickerTags` (array of 6-7 digit alphanumeric codes — SPAC tickers contain letters), `eventType` (one of ~50 canonical values, with `OTHER` as the fallback held under 5%), and `actionableFor` (array of: "traders" | "long_term_investors" | "governance_analysts" | "none"). Prompt-side body cap is 12,000 chars (parser-side cap is 20,000) so Flash-Lite per-call cost stays bounded.
- The LLM response is validated against a JSON schema. On a parse / transport failure, an audit_failure row is recorded and the controller returns 503 — settlement-on-2xx leaves the caller uncharged, and they may retry without paying twice.
- The final summary is persisted to `disclosure_summary`. The summary is generated **once per `rcpt_no` and cached forever** — every subsequent paid call for that filing hits the DB cache, so margins compound as adoption grows.

### Paid API

All paid endpoints sit behind an `X402PaywallInterceptor` + `X402SettlementAdvice` pair. Free endpoints (`/v1/companies`, `/v1/companies/{ticker}`, `/v1/disclosures/recent`, `/v1/pricing`, `/.well-known/x402`) carry no payment challenge so an agent can browse before paying.

Request flow on a paid endpoint:

1. Client sends request without a payment header → server returns 402. Per the [x402 v2 transport spec](https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md), the `PAYMENT-REQUIRED` response header carries the base64-encoded `PaymentRequired` payload (with the `bazaar` extension declaring an input/output schema for AI-agent discoverability), and the body keeps a v1-compatible JSON copy so older clients keep working.
2. For `pricingMode = PER_RESULT` endpoints (e.g. `/v1/disclosures/by-ticker?ticker=…&limit=N`), the server multiplies the unit price by the count query parameter, clamped at `maxCount`, and signs the resulting `0.005 × N` USDC into `accepts[0].amount` so the agent sees the exact charge before authorising.
3. Client signs an EIP-3009 `TransferWithAuthorization` for the declared amount and retries the same request with the base64-encoded payload in `PAYMENT-SIGNATURE`. The legacy `X-PAYMENT` header (v1) is also accepted for the 0.2.x SDK / MCP releases still in the wild.
4. Interceptor first checks Redis `payment_sig:{hash}` with SETNX. If already present, returns 402 "signature reused". A garbled (non-base64 / non-JSON) header lands on **HTTP 400** per the v2 transport-spec error table — 402 is reserved for "no payment provided" or "payment failed".
5. **Resource URL binding** — the interceptor compares `paymentPayload.resource.url` to the actual request URL (path + query). A mismatch is rejected with 402 + lock release. This is the server-side enforcement that prevents a signature for the cheap `?rcptNo=…` endpoint from being replayed against the more expensive `?ticker=…&limit=N` endpoint — the EIP-3009 signature itself binds amount/nonce/validity, not URL, so URL binding is purely server policy.
6. Interceptor calls the Coinbase CDP facilitator's `/verify` endpoint (Ed25519 JWT auth) with the payment proof. If invalid, releases the Redis signature and returns 402 with the facilitator error.
7. The controller runs. Business logic has no awareness of payments.
8. On a 2xx controller response, `X402SettlementAdvice` calls the facilitator's `/settle` endpoint and, on success, writes the settlement proof to the `PAYMENT-RESPONSE` response header (with `X-PAYMENT-RESPONSE` echoed alongside for v1 clients) and appends a row to `payment_log`. **If `/settle` throws or returns `success: false`, the response is rewritten to the x402 v2 settle-failure shape: HTTP 402 + `PAYMENT-RESPONSE` header carrying the failure `SettlementResponse` (success: false, errorReason, payer) + an empty JSON body. This drops the controller payload so a facilitator outage cannot leak paid data unpaid, and matches the spec's "payment not captured, retry with a fresh signature" semantics rather than confusing clients with a generic 502. The Redis signature lock is also released — the on-chain EIP-3009 nonce was not consumed, so the same authorisation is still cryptographically valid for the client's retry once the facilitator recovers.** On non-2xx controller status, settlement is skipped and the signature is released from Redis so the client can retry without paying twice.

### Endpoints

See `README.md` (Pricing section) and the live machine-readable descriptor at [`/v1/pricing`](https://api.koreafilings.com/v1/pricing) for the canonical price list. Schemas live in the OpenAPI spec generated by springdoc, exposed at [`/v3/api-docs`](https://api.koreafilings.com/v3/api-docs) and a Swagger UI at [`/swagger-ui`](https://api.koreafilings.com/swagger-ui/index.html). Agent-driven discovery is at [`/.well-known/x402`](https://api.koreafilings.com/.well-known/x402).

## Non-Functional Requirements

- **Latency**: p95 response time for cached summary reads ≤ 200 ms. Cold (uncached) reads ≤ 12 seconds — body fetch from DART (~2-5 s) + Gemini call (~5-7 s) + write-back, dominated by the upstream LLM. Free endpoints (`/v1/companies`, `/v1/disclosures/recent`) ≤ 100 ms p95 — pg_trgm fuzzy search across 3,961 rows is sub-50 ms in practice.
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

### Folded into v1.1 (round-11, 2026-05-06)

- **Body fetch via DART `/document.xml`** — was originally scoped as the v1.2 "deep analysis" tier. Pulled forward into v1.1 as part of the lazy-generation pivot, served via the same standing `/v1/disclosures/summary` and `/v1/disclosures/by-ticker` endpoints at the same flat 0.005 USDC price. Quantitative events (rights offerings, debt issuance, supply contracts) now surface concrete amounts, dilution %, and counterparty names directly in `summaryEn`.
- A future **tiered pricing iteration** (event-type clusters, e.g. LOW/STANDARD/HIGH) is tracked in `docs/ROADMAP.md` but is intentionally deferred until traffic data shows which event types drive disproportionate paid volume.

## Success Metrics

The MVP is successful if, by the end of month 3 post-launch:

- At least 10 distinct wallet addresses have made paid calls.
- Monthly recurring revenue ≥ $100 USDC.
- At least one named integration (someone publicly saying "we use Korea Filings in our agent").
- x402scan ranking: top 20 by weekly transaction count in the "data" category.

Stretch: monthly revenue ≥ $500, and at least one external paying wallet returning week-over-week for ≥ 4 consecutive weeks (sustained agent integration, not a probe).

## Failure Criteria (When to Pivot)

If by end of month 6:

- Total lifetime revenue < $150
- No repeat users (same wallet calling twice on different days) numbering more than 3
- Discovery-surface traffic dominated by indexers / crawlers rather than payment-capable agents

... then reassess: introduce tiered pricing (event-type clusters) to lift average revenue per call, pivot toward a Korean address-normalisation API (higher call volume but lower margin per call), or pivot toward a Chaebol graph API (higher marginal value per call, different customer).
