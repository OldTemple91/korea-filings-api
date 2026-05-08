# Roadmap — Korea Filings

Six-week plan from zero to public launch, sized for ~10 hours of focused work per week.

## Week 1 — Foundation and Ingestion

Goal: new Korean filings land in Postgres within 60 seconds of publication. No LLM yet. No payment yet.

**Day 1 (Saturday)**
- Initialize Gradle project from `build.gradle.kts` template.
- Verify `docker-compose up` brings Postgres and Redis healthy.
- Create `Disclosure` entity, `DisclosureRepository`, Flyway migration `V1__create_disclosure.sql`.
- Write a failing Testcontainers integration test that asserts the repository can save and retrieve a disclosure.

**Day 2 (Sunday)**
- Sign up at opendart.fss.or.kr and obtain a DART API key. Store in `.env` (never in code).
- Implement `DartClient` with WebClient, timeouts (5s connect, 10s read), and one retry.
- Write unit tests against a mock server (WireMock).
- Implement `DartPollingScheduler` with `@Scheduled(fixedDelay = 30000)`. Disable by default in test profile.
- Manual end-to-end smoke test: run against real DART, confirm new filings accumulate in Postgres.

Deliverable: run locally, wait 5 minutes, see real DART filings in the `disclosure` table.

## Week 2 — LLM Summarization Pipeline

Goal: every new disclosure has an English summary within 60 seconds of ingestion.

**Day 1**
- Sign up at Google AI Studio, generate API key, charge $10 to start.
- Implement `LlmClient` interface with `summarize(DisclosureContext) → SummaryResult`.
- Implement `GeminiFlashLiteClient`. Use Google's Gen AI SDK for Java (or hand-rolled WebClient — SDK is lighter weight here).
- Write a prompt template that asks for strict JSON output matching the schema in PRD.
- Implement `ComplexityClassifier` — pure rules, no ML. Keyword-based on `report_nm`.
- Write unit tests with sample DART responses.

**Day 2**
- Implement `SummaryService` orchestrator.
- Implement Redis-backed `SummaryJobQueue` (LPUSH/BRPOP semantics).
- Wire `DartPollingScheduler` → `SummaryJobQueue.push(rcptNo)` after persisting a new filing.
- Implement `SummaryJobConsumer` running on a dedicated thread pool.
- Manual end-to-end: wait for a real disclosure, see it summarized end-to-end.
- Add `llm_audit` logging so every call is recorded.

Deliverable: run locally for a day, open pgAdmin, see `disclosure_summary` rows for recent filings with plausible English.

## Week 3 — x402 Paywall

Goal: a paid endpoint works end-to-end on localhost. A test client can pay USDC on Base Sepolia testnet and retrieve a summary.

**Day 1**
- Read the x402 v2 spec on docs.x402.org. Understand the three-party flow: client, resource server, facilitator.
- Create a Coinbase developer account, generate a test wallet, get some Base Sepolia USDC from the faucet.
- Implement `FacilitatorClient` — HTTP client for Coinbase's public facilitator endpoints.
- Implement `PaymentStore` (Redis SETNX + release).
- Implement `@X402Paywall` annotation and `X402PaywallInterceptor`.
- Register the interceptor in `WebMvcConfigurer`.

**Day 2**
- Annotate `GET /v1/disclosures/summary` with `@X402Paywall(priceUsdc = "0.005", network = "base-sepolia")`.
- Implement the controller (input via `?rcptNo=…` query param so the bazaar v1 schema can declare it).
- Write a test client in Python or TypeScript that:
   1. Calls the endpoint without payment → receives 402 with requirements.
   2. Signs a USDC transfer with ethers.js.
   3. Retries with `PAYMENT-SIGNATURE` header → receives 200 with summary.
- Debug until the flow works end-to-end.

Deliverable: a demo video-able payment flow on localhost against testnet USDC.

## Week 4 — Public Deployment

Goal: the API is reachable at `api.{domain}/v1/...` from the public internet, with HTTPS, not exposing the home IP.

**Day 1**
- Register the chosen domain (`koreafilings.com`).
- Point the domain's DNS to Cloudflare (transfer nameservers).
- Install `cloudflared` on the production VM.
- Create a Cloudflare Tunnel in the dashboard, connect to the server, route `api.{domain}` → `http://app:8080` (inside Docker Compose).
- Verify external reachability with `curl https://api.{domain}/v1/pricing`.

**Day 2**
- Switch from Base Sepolia to Base mainnet in production config.
- Purchase $5 worth of real USDC on Base to test live flow.
- Run the test client against production once. Confirm settlement appears in the payer wallet and funds arrive in the merchant wallet.
- Set up Prometheus + Grafana in Docker Compose. Import a basic Spring Boot dashboard.
- Install `monit` or equivalent for process health.
- Write the status page (can be a static HTML on Cloudflare Pages).

Deliverable: a working paid API at a real URL, observable in Grafana.

## Week 5 — Distribution

Goal: external users can find this API and use it.

**Day 1**
- Write `docs/openapi.yaml` by hand or auto-generate with springdoc. Host at `docs.{domain}`.
- Write example `curl` commands and a 20-line Python SDK (`pip install dart-intel`). Publish to PyPI under test account first, then real.
- Write a 40-line TypeScript SDK. Publish to npm.
- Write a polished English landing page at `{domain}` (single HTML file hosted on Cloudflare Pages).

**Day 2**
- Implement the MCP server wrapper in `com.dartintel.api.mcp`. Expose four tools: `list_recent_disclosures`, `get_disclosure_summary`, `search_by_ticker`, `filter_by_event`.
- Package the MCP server as a separate lightweight runner (Node.js stdio transport around the HTTP API).
- Register on modelcontextprotocol.io directory.
- Register on x402scan via their discovery document endpoint. Confirm the service shows up.
- Submit to `awesome-mcp-servers` GitHub repo via PR.
- Draft a HN "Show HN" post, sleep on it.

Deliverable: a person who has never heard of the project can find the service via x402scan, MCP registry, or Google.

## Week 6 — Launch

Goal: organic agent traffic on the paid path.

**Day 1**
- Post the prepared Show HN draft on Hacker News on a Tuesday or Wednesday morning US time.
- Cross-post on r/LocalLLaMA, r/MachineLearning, r/ClaudeAI with angle appropriate to each.
- Cross-post on GeekNews (Korean) and OKKY.
- Monitor Grafana for traffic and errors. Stay available for the first 6 hours to answer questions.

**Day 2**
- Review metrics. Document bugs found during launch.
- Optionally publish a write-up covering technical choices, cost structure, and launch numbers (however embarrassing).
- Start a changelog at `{domain}/changelog`.
- Stop coding. Watch the data for a week before deciding what to build next.

Deliverable: at least one paid call from an external wallet. Plus, honest metrics written down for future iteration.

## Post-Launch (Weeks 7+)

Do not commit to features ahead of time. Look at what the data says. Some likely directions depending on what the telemetry shows:

- If traffic is dominated by `/latest`: add SSE streaming endpoint.
- If traffic concentrates on specific tickers: add a cheap "watchlist" endpoint that bundles multiple filings per call.
- If customers ask for earnings data: build earnings call summary as a separate product line under the same domain.
- If volume stays low: diagnose (pricing? discoverability? wrong customer?) before building anything new.

## v1.1 — Agent-Friendly Discovery (shipped 2026-04-29)

Replaced the rcpt_no-only entry point with a name-based agent flow.
The original v1 forced callers to know a 14-digit DART receipt
number, which no LLM has in its training data and no agent can
derive from a company name. v1.1 turns the service into a 1 free +
1 paid call sequence:

```
find_company("Samsung Electronics")  → free, returns ticker
get_recent_filings("005930", 5)      → 0.005 × 5 = 0.025 USDC, returns batch
```

What ships:

- `GET /v1/companies?q=…`            — fuzzy name + ticker search (free)
- `GET /v1/companies/{ticker}`        — single lookup (free)
- `GET /v1/disclosures/recent`        — metadata feed across the market (free)
- `GET /v1/disclosures/by-ticker?ticker=…&limit=N` — batch summaries for one company (paid 0.005 × N USDC)
- `GET /v1/disclosures/summary?rcptNo=…` — kept for direct receipt lookup (paid 0.005 USDC)
- `@X402Paywall(pricingMode = PER_RESULT)` — declarative dynamic pricing
- DART corpCode.xml sync on bootstrap + daily 09:30 KST refresh
- Python SDK 0.2 + MCP server 0.2 expose all four flows
- All summaries remain metadata-only — same Gemini cache as v1

## v1.1 lazy + body fetch (round-11, shipping 2026-05-06)

The v1.2 "deep analysis" plan that previously lived here was
collapsed into v1.1 once a closer look at the v1.1 stored summaries
showed that ~50% of `summaryEn` characters were filler about
"details are in the filing body" — exactly because the LLM only
ever saw the title. Rather than charging extra for a separate
"deep" tier, the standing 0.005 USDC endpoint now uses body-aware
summarisation, with the body fetched lazily on the first paid call
per rcpt_no and cached forever.

**Built:**

1. **`DartClient.fetchDocument(rcptNo)`** — single GET to
   `https://opendart.fss.or.kr/api/document.xml`, separate
   Resilience4j `dart-document` instance (breaker / retry /
   30-rpm rate limiter), 5 MB body cap, raw JDK HTTP client to
   avoid the Reactor Netty data-buffer truncation issue.
2. **`DartDocumentParser`** — jsoup-based ZIP walker that keeps
   `.html` / `.htm` / `.xml` entries, strips markup, normalises
   whitespace, truncates at 20,000 chars. Image attachments are
   skipped; corrupt ZIPs raise `IllegalStateException` (caller
   degrades gracefully to title-only).
3. **`disclosure.body` TEXT column** (V13) — caches the parsed
   body. NULL = not yet fetched. Populated lazily by
   `SummaryService.ensureBody` on the first paid call via a
   short `REQUIRES_NEW` transaction.
4. **Lazy summarisation in `DisclosuresController`** —
   `/summary?rcptNo=…` and `/by-ticker?ticker=…` cache miss
   triggers `SummaryService.summarize` synchronously inside the
   request thread. The existing single-flight Redis SETNX lock
   guarantees one LLM call per rcpt_no even under concurrent
   paid requests; a 503 on generation failure leaves the caller
   uncharged via settlement-on-2xx.
5. **Gemini prompt update** — branches on body presence: when
   the body is available, the model leads with concrete numbers
   (KRW / USD amounts, dilution %, counterparty, dates);
   otherwise falls back to title-only summarisation. Prompt-side
   body cap is 12,000 chars (parser cap is 20,000) to bound
   Flash-Lite per-call cost without hurting summary quality.
6. **Eager pipeline disabled** — `DartPollingScheduler` no
   longer pushes to `summary_job_queue`;
   `summary.consumer.enabled` and `summary.retry.enabled` default
   to `false`. The classes stay in code so an offline backfill
   can flip them on via env var.

**Guardrails preserved:**

- Same flat 0.005 USDC price for `/summary` and per-result
  `/by-ticker × limit`. No tier change at the surface.
- Cache invariant ("margin engine") unchanged — same rcpt_no
  always serves the same summary, generated once globally, paid
  once at the LLM provider.
- Body fetch failures degrade gracefully to title-only
  summarisation; no paid request is ever killed by an
  unavailable body endpoint.

**Future tiered pricing** — if later traffic data shows
quantitative events (rights offerings, debt issuance, supply
contracts) drive disproportionate paid volume, the next
iteration will introduce event-type clusters
(LOW/STANDARD/HIGH) priced at the ingestion-time keyword
classification step. That iteration is intentionally deferred
until there is enough adoption to make the tier boundaries
data-driven.

## v0.4.x — Operations + Observability (shipped 2026-05-04 to 2026-05-06)

A run of post-launch maintenance work driven by what actually shows
up in production logs. None of it changes the product surface; all
of it makes the next launch decisions evidence-based.

**Round-7 ops hardening (2026-05-04, commit `93e6c68`).**
[`docs/RUNBOOK.md`](RUNBOOK.md) — 12-scenario incident playbook with
exact commands (API down, Postgres outage, Flyway migration error,
secret rotation, VM rebuild, double-charge reconciliation).
[`docs/SLO.md`](SLO.md) — committed targets (99.0% monthly availability,
p95 ≤ 300 ms cached / ≤ 100 ms free / ≤ 8 s cold, 5xx < 0.5%/day).
`scripts/pg-backup.sh` — encrypted pg_dump every 6h via cron, age
public key on the VM, private key off-VM in iCloud-synced Apple
Notes; 7-day local retention, R2 off-site copy block ready (commented
out pending bucket setup). V9 migration adds
`disclosure_summary.prompt_version` for re-summarisation tracking.
Cloudflare edge cache rule on `/v1/disclosures/recent` (30s TTL) so
repeat polling lands at the edge instead of waking up Tomcat.

**Round-8 observability + agent discovery (2026-05-06).**
A round of REQ_AUDIT data collection after round-7 revealed two
gaps the audit lenses had missed: (1) standard crawler / agent-
discovery files (`/llms.txt`, `/.well-known/agent.json`,
`/robots.txt`, `/sitemap.xml`, root, favicon) were all 404'ing
against AI-agent indexers and search-engine crawlers, and (2)
audit data only living in stdout logs that roll every 50 MB × 5
generations, so any cohort comparison older than ~a week is
impossible.

Three commits resolved both:

- **`a8b77ac` / `4870bbf` — REQ_AUDIT filter + structured 405
  envelope.** `RequestAuditFilter` emits one key=value log line per
  non-GET or 4xx/5xx response: method / path / status / IP
  (CF-Connecting-IP) / UA / sorted query keys / body bytes /
  content-type / boolean presence flags for X-PAYMENT and
  PAYMENT-SIGNATURE (header *values* never logged). `ApiExceptionHandler`
  now returns a JSON envelope on 405 (`error/method/supported/hint/
  discovery` + `Allow` + `no-store`) so misbehaving agents get a
  self-correction hint instead of Spring's empty default.

- **`8b7b7f9` — `/llms.txt` + `/robots.txt` + `/sitemap.xml` + AWP
  `agent.json` + root redirect + favicon.** Static resources for
  llmstxt.org-style agent overview, standard crawler files, and an
  Agent Web Protocol 0.2 manifest that lists every public action
  (free + paid) with method / endpoint / parameters / payment
  pointer. `robots.txt` `Disallow`s the paid endpoints — anonymous
  crawlers can't pay, and there's no business value in indexing a
  402 response. Root path 302-redirects to the marketing landing
  page; favicon returns 204 to stop the access-log noise.

- **`89a40d2` — `request_audit` table + analytics queries.** V10
  migration. `RequestAuditPersister` writes audit rows to Postgres
  via an async bounded queue (10k row capacity, batches up to 100
  rows per round-trip, single daemon thread). 90-day retention via
  nightly prune at 04:00 UTC, comfortably outside trading hours and
  well after the 06:00 UTC encrypted backup. [`docs/ANALYTICS.md`](ANALYTICS.md)
  documents the reusable SQL playbook: per-day UA category
  breakdown, discovery file probe trend, the 5-step funnel
  (discovery → 402 → signed → settled), week-over-week cohort
  comparison for post-release retrospectives, new-integration
  emergence detection, stuck-loop diagnosis.

The headline finding from the audit run: the paid path is
exercised by internal regression coverage but no external payment-
signing traffic was observed yet. The infrastructure works end-to-
end (the regression suite proves it); the bottleneck is awareness
in the x402-capable indie agent ecosystem, not the paid path
itself.

**TS SDK + ICP repositioning shipped 2026-05-06.** Two follow-up
moves landed the same day the audit finding above was written.
`koreafilings@0.1.0` published to npm — ESM + CJS, viem-based
EIP-712 signing, native fetch, runs unchanged in Node / browsers /
Cloudflare Workers / Deno. Surface mirrors the Python SDK 1:1, so
the same prose and the same agent flow work in either language.
README, landing, PRD, and the HN draft were also rewritten to lead
with the pay-per-call delivery model and to explicitly name three
non-target segments (Korean retail, traditional sell-side, foreign
institutional with Asia desks) so future contributors don't drift
toward features none of them want.

**Round-9 / 10 — payment_log silent-drop P0 + multi-layer defence (same day, 2026-05-06).**

The TS SDK's first live mainnet test (limit=2, 0.01 USDC) returned
a 200 + a settlement header, but `payment_log` had no new row.
Investigation showed the round-7 work had introduced a replay-key
shape (`"nonce:" + 0x + 64-hex` = 72 chars) that exceeded the
`signature_hash VARCHAR(64)` column. Postgres rejected with SQL
state 22001; Hibernate wrapped it in
`DataIntegrityViolationException`; the round-7 idempotency handler
caught the parent class and treated every integrity violation as a
benign duplicate. Every paid mainnet settlement after round-7 was
silently dropped from the merchant ledger. Settlements continued
to land on-chain and clients kept getting their summaries, so the
regression was invisible from outside the database.

Five layered defences shipped:

- **V11 migration** — widen `signature_hash` 64 → 96 (commit `ef68797`).
- **V12 migration** — widen `facilitator_tx_id` 80 → 200 and
  `endpoint` 200 → 500 to defuse the same column-truncation class
  for two more columns identified as latent (commit `f672fa7`).
- **`X402SettlementAdvice` SQLState differentiation** — walks the
  exception cause chain to inspect the underlying JDBC SQLState.
  Only `23505` (UNIQUE) is treated as idempotent; 22001 / 23502 /
  23503 / 23514 surface as a loud reconciliation-failure log +
  `reconciliationFailure=true` flag flowing to `PaymentNotifier`
  and a Micrometer counter.
- **`PaymentLogReconciliationMonitor`** (commit `cb95153`) —
  `@Scheduled` per-minute scan of `payment_log` for rows older
  than 5 minutes with `facilitator_tx_id IS NULL`. Exposes
  `payment_log_reconciliation_gap_rows` gauge and
  `payment_log_reconciliation_failures_total` counter on
  `/actuator/prometheus`. Detection now survives a mis-configured
  `X402_NOTIFY_WEBHOOK_URL` — a Grafana alert can fire on either
  signal independently of the webhook path.
- **Integration tests** — `DisclosuresControllerIT.facilitatorValidAcceptance...`
  now asserts a real row lands in `payment_log` with the expected
  shape (the original test only asserted the controller response,
  which would have passed even under the silent-drop bug). New
  `X402SettlementAdviceWiringTest` covers all five persistence
  outcomes (success, 23505 idempotent, 22001 truncation, 23502
  not-null, DB-down) with mocked deps. Both run in CI.

Round-10 also closed the rest of the multi-agent review's findings
across both languages and three doc surfaces:

- **TS SDK 0.1.1 → 0.1.2** — `KNOWN_DOMAINS` allowlist hard-fails
  on a non-canonical USDC contract (defends against a malicious
  server substituting a different verifyingContract), structured
  `PaymentError.detail` (object with `expected` / `observed` /
  `recommendation` so an LLM agent can branch without parsing the
  human message), `lastSettlementError` field disambiguates the
  three previously-identical null-`lastSettlement` cases (free
  tier / header absent / header malformed-or-oversize),
  `viem 2.21.0` exact pin (no caret) for stable EIP-712 typed-data
  encoding, viem encoding canary test fails before any silent
  bump can ship a broken SDK.
- **`/.well-known/x402` now publishes `extra`** — the canonical
  EIP-712 `name` + `version` for the running chain, so SDK 0.1.1+
  KNOWN_DOMAINS consumers can verify the contract from the
  discovery doc before issuing a 402.
- **`llms.txt` accuracy** — TS SDK install line + `viem 2.21.0`
  pin note + `rcptNo` / `ticker` regex format constraints
  (mirrors the server's `@Pattern` rules) for agents not using
  the SDK.
- **405 envelope absolute URLs** — `hint` and `discovery` emit
  full `https://api.koreafilings.com/...` URLs reconstructed from
  Tomcat's already-trusted forward headers, so a tool-chain agent
  passing the envelope through a multi-step flow does not lose
  origin context.
- **CI** — `.github/workflows/test.yml` runs `gradle test` +
  `npm typecheck/test/build` on every push and PR. Round-9's P0
  was caught by a manual SDK live test, not by CI; that gate is
  now automated.

**Total today**: 9 commits (round-9 P0 fix + round-10 ship +
round-9 / round-10 review fixes), 190 tests green (Java 158, TS
32). Highest-leverage remaining moves: HN Show HN and Smithery
registration.

## x402 Ecosystem Expansion — distribution channels and how mainstream adoption arrives

The pay-per-call x402 model only delivers revenue when an agent or
end user can actually reach the paywall. Today (2026-05) that path
runs through MCP-aware desktop clients (Claude Desktop, Cursor,
Continue) plus hand-rolled SDK integrations — both of which assume
the caller already controls a wallet funded with USDC on Base.
Mainstream sellers and buyers do not. The expansion path below
sketches how the friction layer is expected to peel off over the
next several quarters and which channels the service should be
present in at each stage. Each tier is ordered by friction the
end user sees, not by current adoption volume — Tier 1 already
works and is where direct effort lands, while Tiers 2–4 are mostly
patience plays where the right move is to be discoverable when the
unlock arrives.

### Tier 1 — works today, requires user wallet

| Channel | Friction the user sees | What we do |
|---|---|---|
| **agent.market** (Coinbase official x402 directory) | Coinbase Smart Wallet OAuth — single sign-in, no env vars / private keys | Submit metadata.json PR to `x402-foundation/x402` ecosystem listing. Single shot, ~5 business days review. |
| **Smithery / 1-click MCP install** | MCP install automated; user still brings their own wallet for paid tools | Submit listing when site recovers (intermittent outages observed 2026-05). |
| **Hand-rolled MCP install** | `uv tool install` + `claude_desktop_config.json` edit + private key in env. Verified working with the maintainer's own PAYER wallet on 2026-05-08. | README + dev.to writeup so the install path is documented; this is the SDK-developer entry point, not the mainstream entry. |
| **Direct SDK use** | Agent builder writes `KoreaFilings({ privateKey, network })` themselves | Keep TS / Python / MCP SDKs healthy. Maintain published versions on npm + PyPI. |

### Tier 2 — Q3–Q4 2026 (high-probability unlocks)

#### Coinbase Agentic Browser ⭐⭐
Coinbase is shipping a browser whose default behaviour is "agent
makes the call, the embedded Smart Wallet pays for it." Browserbase
infrastructure handles the headless side; the user types natural
language and never touches a private key. **Currently closed beta.**

Implication for us: once a service is on agent.market, the Agentic
Browser surfaces it automatically. The work to capture Tier 2 is the
same work as Tier 1 — get listed in agent.market.

#### ChatGPT / Claude bundled wallet (rumored)
Both Anthropic and OpenAI have publicly framed agent payments as a
2026 work item but neither has shipped a user-facing wallet inside
the consumer chat product yet. The likely shape:

- User pays the existing subscription (Claude Pro / ChatGPT Plus) in
  fiat to the model vendor.
- Vendor maintains a USDC pool and pays x402-paywalled MCP tools on
  the user's behalf, drawing from a quota inside the subscription.
- For us this would mean a single registration step (be in the
  vendor's MCP marketplace) and then any Claude Pro user calling our
  tool is a paying customer with no wallet UX.

Implication: stay registered with Smithery / Anthropic-adjacent MCP
hubs so we are not the bottleneck when the announcement arrives.

#### Hosted agent platforms (Manus / Heurist / Dify)
B2B path. The platform sells subscriptions to end users in fiat /
local payment rails (KRW for a Korean platform, USD for Manus); the
platform owns a USDC pool and settles with x402 services on the
user's behalf. Some platforms (Manus) have begun x402 integration;
most have not.

Action shape: partnership outreach when the platforms publicly
support x402 — typically not actionable as a one-person effort
until the platform exposes a self-serve listing. Patience play.

### Tier 3 — B2B2C, organic timing

#### Korea Filings as someone else's backend
A SaaS or vertical product can use our SDK as one of several backends
and resell to its own users in fiat. Our wallet ledger only sees the
SaaS company's payer address; the SaaS company sees per-end-user
receipts on its own books.

The path here is purely "be a high-quality SDK with credible
documentation." No specific marketing. Expected to materialise
from organic GitHub discovery + dev.to/HN content over months.

### Tier 4 — what we cannot influence directly

- **OpenAI / Anthropic native wallet rollout timing** (Tier 2 #4-5).
- **Coinbase's commercial roadmap for agent.market** — what categories,
  what fee model, whether they introduce paid placements.
- **Korean fiat / KakaoPay → USDC bridge** for native Korean agent
  apps that want to charge end users in won and pay us in USDC.

We track these passively. None block what we can ship now.

### What this means in practice

1. **Tier 1 #1 (agent.market) is the single biggest leverage move
   available today** — it costs one PR plus a logo, and downstream
   it auto-feeds Coinbase Agentic Browser whenever that opens up.
2. **Tier 1 #2 (Smithery) is a queued retry** — site has had
   intermittent access issues 2026-05; treat as low-priority noise
   until the site is reliably reachable.
3. **Tiers 2–4 are patience plays.** The temptation is to chase
   them by building features; the actual work is to stay registered,
   stay documented, and watch announcements.

The ROADMAP's existing milestones (next paid endpoint, additional
data sources) compete with distribution work for the operator's
time. Distribution wins when revenue is at zero, because no
additional feature lifts revenue in a vacuum. Once the first
external paying wallet has a non-trivial monthly cadence, the
balance shifts back toward features.

## Guardrails Throughout

- Built on public DART data only — no proprietary or third-party code, data, or accounts referenced at any point.
- Every external dependency (DART, Gemini, OpenAI, Coinbase) gated behind a client class with timeouts and a circuit breaker.
- Secrets only in `.env`, never in the repo. `.env.example` documents all variables.
- Before every week's work: re-read `CLAUDE.md` and `PRD.md`. Update them if reality has diverged.
- Keep recurring infrastructure spend bounded by the previous month's revenue. If revenue is not at $50/month by month 4, scale costs down, not up.
