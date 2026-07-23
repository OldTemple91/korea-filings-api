# STATUS — where we left off (2026-07-23, post-round-18)

Read this first when picking up on a different machine. Summarises what is
live, what's next, and the minimum setup to keep moving.

## Operator essentials

**Incident playbook** → [`docs/RUNBOOK.md`](RUNBOOK.md) — every scenario (API down, Postgres outage, Flyway migration error, secret rotation, VM rebuild, double-charge reconciliation) has exact commands.

**Service objectives** → [`docs/SLO.md`](SLO.md) — what we promise, how it's measured.

**Backups** → `scripts/pg-backup.sh` runs every 6h via cron on the VM, encrypted with `age`. Local retention 7 days. Private key stored in iCloud-synced Apple Notes (off-VM by design — backups are useless if the key sits next to the data they protect). **Off-site copy via R2 + rclone is the remaining gap** before treating MVP as production-ready.

**Analytics & KPIs** → [`docs/ANALYTICS.md`](ANALYTICS.md) — reusable SQL playbook over the `request_audit` table: per-day UA category breakdown, discovery file probe trend, the 5-step funnel (discovery → 402 → signed → settled), week-over-week cohort comparison for post-release retrospectives, new-integration emergence detection, stuck-loop diagnosis. Copy-paste runnable from a `psql` session on the production VM.

## TL;DR

- **Weeks 1–5 complete.** Ingestion, summarisation, x402 paywall, public
  deployment, landing page, Python SDK, MCP server, OpenAPI docs — all
  live in production at `api.koreafilings.com`.
- **Round-18b/c — follow-ups from post-deploy live review (2026-07-23).**
  (b) DART also pads `report_nm` in the INTERIOR (before parenthetical
  remarks) — live inspection found 2,012 rows still shipping multi-space
  runs after V15; `Disclosure.normalizeReportNm` collapses all runs,
  V16 repaired the corpus. Uncached `/recent` rows now run the
  pure-function classifier on the fly for `reportNmEn` instead of
  mislabelling "Other Disclosure".
  (c) The 402 challenge now carries a `howToPay` block (SDK
  quickstarts, free `/sample` pointer, docs link) — every organic
  prospect observed in July died at the machine-only 402 with zero
  retries. `/llms-full.txt` and `/.well-known/llms.txt` serve the
  canonical llms.txt (both were live 404s). SDK models caught up with
  the round-15b/17a/18 wire fields that typed models were silently
  dropping: Python 0.3.3 on PyPI; TypeScript 0.1.4 built and
  changelogged, npm publish pending a fresh token.
- **Round-18 — the API answers in English (2026-07-23).** A response
  audit found the product was shipping Korean on the surfaces that
  matter most to an English-consuming agent. Three defects, all fixed
  without any LLM spend:
  (1) `disclosure.corp_name_eng` had existed since V1 but was written
  NULL on every row, because DART's `/list.json` carries only the
  Korean `corp_name`. The free `/recent` feed therefore described each
  filing in Korean, and — separately — the Gemini prompt had been
  receiving the literal string `"n/a"` for the English company name on
  every summary ever generated. Ingestion now denormalises
  `company.name_en` (populated for 100% of the KRX directory) off the
  same lookup that already resolves the ticker, so both the payload
  and future prompts get the real English name.
  (2) The paid `/summary` and `/by-ticker` payloads carried no company
  name at all — only `rcptNo` and `tickerTags` — so a buyer received an
  English summary plus a six-digit number and had to make a second call
  to learn which company it concerned. Paid rows now carry `corpName`,
  `corpNameEn`, `reportNm`, and `reportNmEn`.
  (3) DART pads `report_nm` to a fixed width; the trailing spaces
  leaked into the JSON and broke consumer-side string matching. Trimmed
  at ingestion, with `V15` repairing the existing corpus.
  `reportNmEn` is a deterministic English label derived from the
  rule-classified `eventType` (54 event types mapped) — a category
  label, not a literal translation, so it costs nothing and cannot
  hallucinate. The Korean values stay in the payload as the canonical
  DART originals.
- **Round-17a — response trust-gate + free pre-purchase signal (live, 2026-06-16).**
  Came out of a parallel buyer-worthiness investigation (real paid
  responses scored 2.6/10 by a 5-persona panel; the two structural
  gripes were "can't tell a quantitative row from filler BEFORE paying"
  and "no audit path / differentiation vs free englishdart"). 17a ships
  the two cheapest, zero-LLM-risk response changes that attack those:
  (1) `sourceUrl` — the canonical DART viewer link
  (`https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcptNo}`) on both the
  paid `/summary` rows and the free `/recent` feed; a paraphrase can't
  enter a quant/research workflow without an audit path to the
  authoritative filing, and this also makes the long-standing (and
  previously false) `llms.txt:73` claim "every summary links back to
  the DART original URL" true. (2) `numericExpectation` (`HIGH`/`LOW`)
  derived purely from the eventType the ingestion-time classifier
  already assigns — surfaced FREE on `/recent` so an agent can decide,
  before paying, whether a filing class normally carries an extractable
  number (importanceScore rates newsworthiness, not number-presence).
  Both are pure functions (no DB, no LLM, no migration, no
  hallucination). The substantive structured-numeric work (keyFacts
  object extracted by the LLM + a numericFieldCount derived from it) is
  the deliberately-sequenced round-17b — it touches the Gemini
  responseSchema + entity + persistence and can't be validated while
  organic demand is ~0, so it ships as its own focused round.
  Honest framing carried from the investigation: with ~0 organic paid
  calls these response changes can't be validated by inflow — they
  reshape the discovery-visible schema and de-risk the first real
  buyer's decision; they don't manufacture demand.
- **Round-16 — P0 paid-summary regression fix + paid-call observability (live, 2026-06-16).**
  Surfaced while investigating the first-ever settlement from a
  non-maintainer wallet (2026-06-12, `0x9CC4…b176` — identified as
  Coinbase CDP **Bazaar Discovery** validating the service, with
  `CoinbaseBazaarDiscovery/1.0` interleaved with the paid calls in
  `request_audit`). Checking what data that paid call actually
  returned exposed a P0: round-15c writes a rule-based classifier
  stub (`summary_en = NULL`) for every ingested filing, but the two
  paid handlers checked only row *existence* (`containsKey` /
  `isPresent`), not LLM-summary *presence*. So every filing ingested
  since 2026-05-29 returned a null `summaryEn` on a paid call — the
  customer paid full price for an empty product. The 2026-06-12
  by-ticker call got 5 null summaries; only the `/summary` example
  rcptNo survived because it had a real pre-15c summary. Fixes:
  (1) `getByTicker` generates when the row is absent OR a stub
  (`!hasLlmSummary()`), and filters stubs out of the delivered list;
  (2) `getSummary` filters the cache lookup on `hasLlmSummary` so a
  stub falls through to generation, and only returns 200 when the LLM
  text is actually present (else 503, uncharged via settlement-on-2xx).
  Guardrail: new `PaidSummaryQualityMonitor` emits
  `paid_summary_degraded_total{endpoint}` (+ WARN) whenever a paid
  slot can't be served a real summary — the signal whose absence let
  this regression hide for two weeks. Observability: the two paid
  endpoints joined the `request_audit` GET-2xx whitelist, so a settled
  paid call's IP / UA / timing are now captured (not just the 402
  challenge) — which is how a real external customer will be told
  apart from a catalog verifier next time. Regression test seeds a
  stub-only filing and asserts a paid `/summary` never returns
  200-with-null. No schema change.
- **Round-15c — rule-based classifier at ingestion (live, 2026-05-29).**
  Round-15b's enrichment did the right thing for the cached path
  but had nothing to enrich in production: the same funnel review
  showed zero cache writes since the round-12 self-test era because
  round-11's lazy-summarisation model only writes a
  {@code disclosure_summary} row when an agent actually pays for a
  summary, and the streak is 21 days. The free {@code /recent}
  feed therefore carried no AI metadata on freshly-ingested
  filings — defeating the very enrichment 15b shipped. Round-15c
  closes the gap without spending LLM dollars by deriving
  {@code importanceScore} / {@code eventType} / {@code tickerTags}
  / {@code actionableFor} from the DART {@code report_nm} via a
  rule-based classifier whose 80+ pattern table was extracted from
  the 6,211 LLM-classified rows already in the production cache
  (`SELECT report_nm, event_type, AVG(importance_score) FROM
  disclosure_summary JOIN disclosure …`). The classifier runs
  in-process at ingestion inside the same {@code persistBatch}
  transaction, costs zero ongoing dollars, adds zero latency to the
  poll loop, and writes a "stub" {@code disclosure_summary} row
  with {@code summary_en = NULL}, {@code model_used = 'rule-v1'},
  {@code prompt_version = 0}. The first paid call then triggers the
  existing {@code SummaryService.summarize} flow which calls the LLM
  and UPDATEs the same row in place via
  {@code DisclosureSummary.overlayLlmSummary} — preserving
  {@code generated_at} and letting the LLM refine the classifier's
  initial importance estimate. V14 migration relaxes
  {@code summary_en NOT NULL}; nothing else changes shape. The
  margin engine ({@code summarize-once, cache-forever}) is
  unaffected — the LLM still runs at most once per
  {@code rcpt_no}. Effect: from the moment 15c ships, every new
  DART filing surfaces with importance + event type on {@code /recent}
  without anyone paying first.
- **Round-15 — free-API observability + enriched /recent (live, 2026-05-29).**
  Funnel review on 2026-05-28 revealed two related blind spots. The
  audit table was undercounting actual agent activity by orders of
  magnitude — `RequestAuditFilter` skipped every GET 2xx, so the
  3,465 lifetime successful reads of `/.well-known/x402` visible in
  Prometheus translated to literal zero rows in `request_audit`;
  IPs / UAs / params for who actually consumed the free API were
  lost. And the free `/recent` feed was effectively "what's the
  Korean filing system saying right now" with no signal about which
  rows were worth paying for. Round-15a opted the canonical free
  paths back into the audit table (`/v1/companies`,
  `/v1/disclosures/recent`, `/v1/disclosures/sample`,
  `/v1/pricing`, `/.well-known/x402`, `/.well-known/x402.json`,
  `/.well-known/agent.json`, `/llms.txt`) — adding maybe 100–200
  audit rows per day, comparable to the existing noise floor and far
  below the 6k+ daily POST 405 the broken axios bot already absorbs.
  Round-15b widened `RecentFilingDto` so rows whose summary is
  already cached carry the AI-derived classification
  (`importanceScore`, `eventType`, `sectorTags`, `tickerTags`,
  `actionableFor`) — zero new LLM cost (the data is already in
  `disclosure_summary`), zero new endpoints, no schema migration.
  The paid `summaryEn` text is deliberately not surfaced; the free
  feed reveals enough metadata for an agent to rank-order which
  filings warrant a 0.005 USDC summary call, not enough to
  substitute for the paid product. Filings without a cached summary
  omit the AI fields entirely (`@JsonInclude(NON_NULL)`) so existing
  agents see a byte-identical response shape. The combined goal is
  to lift the `0.05%` discovery → search conversion observed in the
  funnel review by giving free-feed readers a reason to come back.
- **Round-14b — Swagger UI bare-asset 404 cleanup (live, 2026-05-18).**
  Same audit batch that surfaced the {@code .json}-suffix x402scan
  miss also caught Bing's crawler 404'ing on five swagger-ui asset
  URLs in succession on 2026-05-17 ({@code swagger-initializer.js},
  {@code swagger-ui.css}, {@code index.css},
  {@code swagger-ui-standalone-preset.js}, {@code swagger-ui-bundle.js}).
  Springdoc actually serves those assets one directory deeper at
  {@code /swagger-ui/swagger-ui/<file>} because the swagger-ui jar's
  internal layout puts everything under a {@code swagger-ui/}
  subdirectory and our {@code springdoc.swagger-ui.path} of
  {@code /swagger-ui/index.html} compounds the prefix. Added
  `SwaggerUiAssetRedirectController` that 301-redirects bare
  {@code /swagger-ui/<asset>.{js|css|png}} to the doubled-prefix path
  Springdoc actually serves; matches future asset names without
  enumeration. Excludes {@code .html} so Springdoc's own
  {@code index.html} redirect (the one browsers follow to load the UI)
  is unchanged. Two integration tests added — one verifies the five
  bare-asset paths 301 to the right place, the other guards against
  the regex accidentally shadowing the canonical {@code index.html}
  entry point.
- **Round-14 — `/.well-known/x402.json` alias (live, 2026-05-18).**
  The 2026-05-17 production logs showed `X402-Scanner/1.0` (a
  community x402 ecosystem indexer running from `92.255.110.46`)
  probing `/.well-known/x402.json` — with the `.json` suffix — and
  receiving a 404 because the canonical x402 discovery convention is
  extension-less. Round-14 mounted the existing `WellKnownController`
  discovery handler at both `/.well-known/x402` and the
  `.json`-suffixed alias so suffix-expecting crawlers reach the same
  document instead of falling through. No new fields, no schema
  change; one-line `@GetMapping` value extension. Two integration
  tests added (one for each path) — the canonical path also picked
  up its first direct GET coverage in the process, since prior tests
  only referenced the URL inside other endpoints' responses.
  Distribution-pivot context: `x402-foundation/x402` PR #2236 was
  closed 2026-05-14 by the Coinbase x402 team — the official
  agent.market ecosystem page is being sunset in favour of four
  community-maintained directories (`x402scan.com`, `Agentic.Market`,
  `Pay.sh`, `app.ampersend.ai/discover`). All of the round-12 +
  round-13 discovery-surface investment still applies; the rest of
  the catalog work is now done by being correctly indexed in each
  community aggregator (status: AgentCash unvetted, Agentic.Market
  not yet auto-indexed despite Bazaar extension being live + 8
  CDP-facilitator settlements on file, Pay.sh requires Solana
  settlement and is therefore out of scope, Ampersend Discover is a
  passive autonomous indexer). Round-14's alias closes one small
  gate to the catalog crawlers and is the only code change required
  for the directory pivot — every remaining lever (Merit Systems
  Discord ping for AgentCash promotion, Coinbase Bazaar indexing
  diagnostic) is a process question, not an engineering one.
- **Round-13 — AgentCash discovery surface polish (live, 2026-05-12).**
  Direct testing showed the service was already indexed in the
  AgentCash catalog (the rotating Azure / AWS `node` UA crawlers seen
  in `request_audit` from 2026-05-07 onward turned out to be the
  catalog refresh layer), but only as "newer, unvetted" — visible
  with `agentcash search ... --broad` (ranks 1 – 8 on `"DART
  disclosure"`) and on the website's "Search Live Capabilities"
  panel, hidden behind the default-trust filter that the CLI and
  most agent hosts apply. Three small fixes shipped to remove the
  obvious anti-signals: (1) advertised the already-live
  `/llms.txt` as `service.llms_txt` + root `llms_txt` in
  `/.well-known/x402`; (2) added root-level `name` + `url` so
  catalog crawlers display the brand instead of the bare origin
  host; (3) removed the cosmetic
  `apiKey`-typed `x402Payment` / `x402PaymentLegacy` OpenAPI
  security schemes that made `agentcash discover` label every paid
  endpoint as `apiKey + paid` and therefore filter the service out
  of "no API key" agent searches. Per-operation
  `x-payment-info` extension remains attached by
  `X402OpenApiCustomizer` so paid classification is unaffected.
  No new endpoints, no schema migration; the discovery JSON shape
  picks up three new fields. Next move on this lever is a single
  Merit Systems Discord ping with the diff attached to request
  default-tier promotion.
- **Round-11 lazy + body fetch (live, 2026-05-06).** Eager LLM
  calls at ingestion replaced with synchronous lazy generation on the
  first paid call, plus per-filing body fetch via DART's `/document.xml`
  ZIP. Summaries are now generated from filing body text (capped at
  20,000 chars by the parser, 12,000 in the prompt) instead of title
  metadata only — fixes the "details are in the filing body" filler
  that was eating ~50% of summary length on quantitative events. New
  `dart-document` Resilience4j circuit / retry / rate-limiter (30
  rpm) keeps the body endpoint isolated from `/list.json` polling.
  `disclosure.body` TEXT column added via V13. Test count 158 → 177
  green. First body-aware mainnet settlement landed the same day on a
  Samsung Electronics dividend filing — body-derived response carried
  per-share KRW amount, total payout, dividend yields, record date,
  and payment date that the metadata-only response had to defer to
  the filing body itself. ROADMAP v1.2 collapsed into v1.1 — the
  body-fetch tier no longer needs its own price tier because every
  paid call now uses body when available, falling back to title-only
  on 404 / open breaker / parse failure.
- **On-chain x402 settlements verified** on both Sepolia (test) and
  Base mainnet (production). The 2026-05-06 TS SDK live test
  confirmed every layer of the paid path end-to-end including the
  V11/V12 widening + SQLState handler. Awareness work (HN /
  Smithery / TS SDK adoption) is the next bottleneck — the paid
  surface is technically ready ahead of demand.
- **Facilitator**: Coinbase CDP (Ed25519 JWT auth). Bug caught at
  initial mainnet flip: EIP-712 domain `name` was hard-coded to
  "USDC" but the Base mainnet contract returns "USD Coin"; fixed
  via the `X402_TOKEN_NAME` / `X402_TOKEN_VERSION` config knobs.
- **Encrypted Postgres backups** — `scripts/pg-backup.sh` runs every
  6h via cron on the VM. age public key on the server, private key
  stored in iCloud-synced Apple Notes (off-VM). 7-day local
  retention. Off-site copy via R2 + rclone is the remaining gap.
- **Observability surface (round-8, 2026-05-06)** — `RequestAuditFilter`
  emits one structured `REQ_AUDIT` line per non-GET request or any
  4xx/5xx response (method, path, status, IP, UA, query keys, body
  size, content-type, presence of payment headers; **never their
  values**). `RequestAuditPersister` writes the same data to the
  `request_audit` Postgres table for 90-day retention, powering the
  funnel / KPI / cohort queries in
  [`docs/ANALYTICS.md`](ANALYTICS.md).
- **AI-agent discovery surface (round-8, 2026-05-06)** — `/llms.txt`,
  `/robots.txt`, `/sitemap.xml`, `/.well-known/agent.json` (AWP 0.2),
  root 302 to landing, favicon 204 — closes a class of 404s coming
  from common crawlers and AI-agent indexers that probe the
  standard discovery paths (covered by the audit categorisation in
  [`docs/ANALYTICS.md`](ANALYTICS.md)).
- **PyPI packages published** — `koreafilings` 0.3.1 + `koreafilings-mcp` 0.3.0.
- **npm package published — `koreafilings` 0.1.2** (TypeScript SDK,
  ESM + CJS, viem 2.21.0 exact pin, KNOWN_DOMAINS allowlist that
  hard-fails on a non-canonical USDC contract, structured
  `PaymentError.detail`, `lastSettlementError` field for
  disambiguating the three null-`lastSettlement` cases). Brings the
  same surface to the JS/TS agent ecosystem (LangChain.js, Vercel
  AI SDK, Cloudflare Workers, browser-side agents) where Python
  coverage was the previous gap. See
  [`sdk/typescript/CHANGELOG.md`](../sdk/typescript/CHANGELOG.md)
  for the 0.1.0 → 0.1.1 → 0.1.2 progression.
- **CI pipeline** — `.github/workflows/test.yml` runs `gradle test`
  + `npm typecheck/test/build` on every push and PR. Previously,
  tests gated only at the maintainer's laptop; the round-7 silent-
  drop bug surfaced during a manual SDK live test, not in CI. Now
  any future regression of that class is caught before merge.
- **Directory registrations** — x402scan + Glama + mcp.so done;
  Smithery deferred (site outage at submission time, retry).
- **Next**: HN Show HN post on the next available Tue/Wed at 22:00 KST.
  TypeScript SDK landed today (npm `koreafilings@0.1.0`); the launch
  copy now leads with a `npm install` example for the underserved
  buyer segment, with the PyPI / MCP options below it.

## What's live

| surface | URL | notes |
|---|---|---|
| Landing page | https://koreafilings.com | Cloudflare Workers + static assets. Source in `landing/index.html`. |
| API root | https://api.koreafilings.com | Spring Boot on the production VM via Cloudflare Tunnel. |
| Swagger UI | https://api.koreafilings.com/swagger-ui/index.html | Human-readable API docs. |
| OpenAPI spec | https://api.koreafilings.com/v3/api-docs | Machine-readable JSON. |
| Pricing | https://api.koreafilings.com/v1/pricing | Free, lists paid endpoints + x402 wallet. |
| Health | https://api.koreafilings.com/actuator/health | Liveness/readiness. |
| Paid summary | `GET /v1/disclosures/summary?rcptNo=…` | 0.005 USDC on Base mainnet via Coinbase CDP facilitator. |
| Python SDK | https://pypi.org/project/koreafilings/ | `pip install koreafilings` |
| TypeScript SDK | https://www.npmjs.com/package/koreafilings | `npm install koreafilings` (currently 0.1.2) |
| MCP server | https://pypi.org/project/koreafilings-mcp/ | `uv tool install koreafilings-mcp` |
| Source | https://github.com/OldTemple91/korea-filings-api | Private push via `OldTemple91`. |

## Infrastructure

- **Production VM** — small Linux VPS behind Cloudflare Tunnel.
  Reachable as `<PROD_VM>` in the deploy commands below. App runs
  under `docker compose --profile prod` from the project directory.
- **Cloudflare** — DNS, Tunnel (connector running on the VM,
  outbound-only, no inbound ports), Workers hosting
  `koreafilings.com`.
- **PyPI** — both packages published under the maintainer's PyPI
  account.
- **GitHub** — `OldTemple91/korea-filings-api`. Push via stored
  HTTPS credentials on the maintainer's workstation.

## What's left

1. **v1.1 — done (2026-04-29).** Replaced the rcpt_no entry point
   with a name-based agent flow. The natural call sequence is now
   `find_company` → `get_recent_filings` (or
   `list_recent_filings` → `get_summary`). All four flows are live,
   and on-chain settlements have confirmed both fixed-price and
   per-result pricing modes work end-to-end.

   **v0.3 transport-spec pass (2026-05-04).** Migrated paid endpoints
   to query parameters (`/v1/disclosures/summary?rcptNo=…`,
   `/v1/disclosures/by-ticker?ticker=…&limit=…`) so the bazaar v1
   schema can declare the inputs. Adopted x402 v2 transport headers
   (`PAYMENT-SIGNATURE` / `PAYMENT-RESPONSE`, with `X-PAYMENT` /
   `X-PAYMENT-RESPONSE` accepted as compat). Settle-failure now
   matches the spec (HTTP 402 + failure SettlementResponse in
   `PAYMENT-RESPONSE` + empty body) instead of leaking paid data on
   facilitator outage. SDK is at 0.3.1 on PyPI, MCP at 0.3.0.
   ROADMAP.md carries the full ship summary.

   **v0.4 audit pass (2026-05-04).** Closed 3 P0 + 16 P1 items from
   a multi-agent code review (Spring Boot / security / x402 spec /
   general). The biggest correctness fix: **resource URL binding** —
   the interceptor now compares the signed `paymentPayload.resource.url`
   to the actual request URL and refuses cross-endpoint replay (a
   signature for `?rcptNo=…` (0.005 USDC) can no longer be replayed
   against `?ticker=…&limit=50` (up to 0.25 USDC)). Other highlights:
   single-flight Redis lock around the LLM call so concurrent
   consumers never double-charge Gemini, `REQUIRES_NEW` on the audit
   writer to keep the audit-first-commit invariant under nested
   transactions, malformed `PAYMENT-SIGNATURE` → 400 (was 402) per
   spec, `@Pattern` validation on `rcptNo` / `ticker`, Redis
   `requirepass`, Tomcat `internal-proxies` allowlist, actuator
   `git.mode=simple`, Hikari pool sizing, and HTTP-fetch lifted out
   of `@Transactional` in the DART poller / `corpCode` sync.
   See commit `e5cd9ae` for the full diff.

   **Round-9 / 10 — payment_log silent-drop P0 + multi-layer defence (2026-05-06).**
   The TS SDK's first live mainnet test surfaced a buried regression
   from round-7: the API returned 200 + a settlement header for paid
   calls while `payment_log` rows were being silently dropped by an
   over-broad `DataIntegrityViolationException` handler. Root cause:
   round-7 changed the replay-key shape to `"nonce:" + 0x + 64-hex`
   (72 chars), but `payment_log.signature_hash` stayed at
   `VARCHAR(64)`. Postgres rejected with SQL state 22001, which the
   handler treated identically to a UNIQUE-constraint duplicate.

   Fix shipped as five layered defences across rounds 9 and 10:

   - **V11 migration** widens `signature_hash` to VARCHAR(96).
   - **V12 migration** widens `facilitator_tx_id` 80 → 200 and
     `endpoint` 200 → 500 to defuse the same column-truncation
     class for two more columns the same review identified as
     latent.
   - **SQLState differentiation** — `X402SettlementAdvice` walks
     the cause chain to inspect the underlying JDBC SQLException's
     state code. Only `23505` (UNIQUE) is treated as idempotent;
     everything else (22001, 23502 NOT NULL, 23514 CHECK, FK)
     surfaces as a loud reconciliation-failure log + flag, with
     the {@link PaymentNotifier} alerting on the same flag.
   - **Reconciliation gauge + counter** — new
     `PaymentLogReconciliationMonitor` exposes
     `payment_log_reconciliation_gap_rows` (per-minute Postgres
     scan) and `payment_log_reconciliation_failures_total`
     (incremented from the integrity-violation / DB-down branches)
     on `/actuator/prometheus`. Detection now survives a
     mis-configured `X402_NOTIFY_WEBHOOK_URL` — Grafana alert can
     fire on either signal.
   - **Test integration** — `DisclosuresControllerIT` now asserts
     a real `payment_log` row lands per paid 200 (exact regression
     guard); a new `X402SettlementAdviceWiringTest` covers all
     five persistence outcomes (success, 23505 idempotent, 22001,
     23502, DB-down) end-to-end. CI workflow runs both on every
     push.

   Plus round-10 surface fills: `/.well-known/x402` now publishes
   the `extra` block (canonical EIP-712 `name` + `version` per
   chain) so SDK 0.1.1+ KNOWN_DOMAINS allowlist consumers can
   verify the contract before issuing a 402; `llms.txt` updated
   with TS SDK install + format-constraint regex; 405 envelope
   emits absolute URLs so tool-chain agents passing the body
   through don't lose origin context.

   **Round-8 observability + agent discovery (2026-05-06).** Built
   the analytics infrastructure to answer "did the next release move
   the needle?" with numbers instead of vibes. Three pieces:

   - `RequestAuditFilter` (commits `a8b77ac` / `4870bbf`) — one
     structured `REQ_AUDIT` log line per non-GET or 4xx/5xx response,
     plus a JSON envelope on 405 responses (`error/method/supported/
     hint/discovery` + `Allow` + `no-store`) so misbehaving agents
     get a self-correction hint instead of Spring's empty default.
     The first batch of audit data informed the next two pieces
     below — concrete query / sample shapes live in
     [`docs/ANALYTICS.md`](ANALYTICS.md).
   - **Discovery surface fill** (commit `8b7b7f9`) — `/llms.txt` (AI
     agent overview, llmstxt.org format), `/robots.txt`,
     `/sitemap.xml`, `/.well-known/agent.json` (AWP 0.2 manifest, same
     paid-action surface as `/.well-known/x402` in AWP shape), root
     302 to `koreafilings.com`, favicon 204.
   - **Persistent audit table** (commit `89a40d2`) — V10 migration +
     `RequestAuditPersister` (async bounded-queue, batch inserts up
     to 100 rows/round-trip, 90-day retention via nightly prune at
     04:00 UTC). Header *values* never enter the DB — only the
     boolean presence flag, since `X-PAYMENT` and `PAYMENT-SIGNATURE`
     carry signed nonces. Powers the SQL playbook in
     [`docs/ANALYTICS.md`](ANALYTICS.md).

2. **v1.1 lazy + body fetch — round-11, 2026-05-06.** The body fetch
   originally scoped as v1.2 was pulled forward into v1.1 as part of
   the lazy-generation pivot. The standing `/v1/disclosures/summary`
   and `/v1/disclosures/by-ticker` endpoints now use body-aware
   summarisation at the same flat 0.005 USDC price — quantitative
   events (rights offerings, debt issuance, supply contracts)
   surface concrete amounts, dilution %, and counterparty names
   directly in `summaryEn` instead of falling back to "details are
   in the filing body". A future tiered-pricing iteration is
   tracked in [`docs/ROADMAP.md`](ROADMAP.md) but is not on the
   v1.1 critical path.

3. **Directory registrations**: x402scan ✅ (re-registered after v0.3
   migration; UUID `46ef920d-…` preserved, stale path-param
   resources auto-deprecated), Glama ✅, mcp.so ✅, Smithery deferred
   (site outage at submission time, retry). Glama benefits from a
   manual "Rebuild" once the v0.3 SDK / MCP versions propagate to
   PyPI consumers, but it auto-pulls README content, so the listing
   stays current without intervention.

4. **HN Show HN post** — `docs/launch/HN_DRAFT.md` is the copy.
   Targeting Tuesday/Wednesday 22:00 KST. Headline shifted from
   "rcpt_no demo" (the v1 framing) to "1 free call + 1 paid call to
   get Korean filings as English signals", body-aware summarisation
   already in the round-11 ship so the launch leads with delivered
   depth instead of a roadmap promise.

5. **Operational follow-ups (post-launch)**:
   - **PaymentNotifier webhook** — Slack/Discord notification on every
     settled payment. Code is in (`PaymentNotifier`); just needs
     `X402_NOTIFY_WEBHOOK_URL=…` set in the production `.env` and a
     restart. Currently disabled per the boot log.
   - **R2 off-site backup** — pg-backup.sh has a commented-out
     `rclone copy` block waiting on a Cloudflare R2 bucket + API
     token. Without it, a full VM loss takes the local backups with it.
   - **Grafana dashboard** — point a Grafana Cloud free-tier scrape at
     `/actuator/prometheus`. Four metrics that matter: calls/min,
     cache hit ratio, mean LLM cost per cache miss, payer diversity.
   - ~~TypeScript SDK~~ — **shipped 2026-05-06 as `koreafilings@0.1.0` on npm**.
     ESM + CJS, viem-based EIP-712 signing, native fetch (Node 18+ /
     browsers / Workers / Deno).
   - ~~README ICP re-positioning~~ — **shipped 2026-05-06**. Hero copy
     and PRD `Target Users` rewritten to lead with delivery model
     (pay-per-call, no procurement) and explicitly name three
     non-target segments (Korean retail, traditional sell-side,
     foreign institutional with Asia desks). Stretch goal updated
     to a measurable signal: 1 external paying wallet returning
     week-over-week ≥ 4 weeks.
   - **Business analytics prep** — RFM SQL on `payment_log`,
     `/actuator/prometheus` per-funnel-stage counters, pricing
     simulation doc. ANALYTICS.md covers the audit side; payment-side
     analytics is the next layer.

## Mainnet rollback recipe

If a regression makes mainnet payments fail and we need to fall
back to the public testnet facilitator while debugging, swap these
five env vars and bounce the app:

```env
X402_FACILITATOR_URL=https://www.x402.org/facilitator
X402_NETWORK=eip155:84532
X402_ASSET=0x036CbD53842c5426634e7929541eC2318f3dCF7e
X402_TOKEN_NAME=USDC
X402_TOKEN_VERSION=2
# Leave X402_CDP_KEY_ID / X402_CDP_PRIVATE_KEY blank.
```

Then `rsync .env <vm>:/root/korea-filings-api/.env && docker compose --profile prod up -d --force-recreate app`. ~30 seconds.

## Continuing on a different machine — minimum setup

```bash
git clone https://github.com/OldTemple91/korea-filings-api.git
cd korea-filings-api

# Server-side env (for running ./gradlew bootRun locally):
cp .env.example .env
# Fill in: POSTGRES_PASSWORD, DART_API_KEY, GEMINI_API_KEY,
# X402_RECIPIENT_ADDRESS=0x8467Be164C75824246CFd0fCa8E7F7009fB8f720,
# X402_FACILITATOR_URL=https://www.x402.org/facilitator (testnet)
# or the mainnet block once switched.

# Test-client env (for running live payer against prod):
cp testclient/.env.testclient.example testclient/.env.testclient
# Fill in: PAYER_PRIVATE_KEY (a 0x-prefixed 32-byte hex for a wallet
# holding Base Sepolia test USDC), API_BASE_URL=https://api.koreafilings.com.

# SDK local install (already on PyPI, but for editing):
cd sdk/python && python3 -m venv .venv && source .venv/bin/activate
pip install -e . pytest python-dotenv
pytest tests/
python examples/live_payment.py  # costs 0.005 USDC

# MCP local install:
cd ../../mcp && python3 -m venv .venv && source .venv/bin/activate
pip install -e ../sdk/python -e .
koreafilings-mcp  # stdio MCP server
```

## Secrets that are NOT in git (and where they live on the origin machine)

- `.env` — in repo root, Postgres password + API keys + x402 config +
  Cloudflare Tunnel token.
- `testclient/.env.testclient` — payer wallet private key for live tests.
- `~/.pypirc` — PyPI upload token. **Delete after each upload session**
  unless you're planning frequent releases.
- The production VM keeps its own copy of `<REMOTE_REPO_DIR>/.env` —
  rsync from the maintainer's workstation if the contents diverge.

## Commands cheat sheet

```bash
# Rebuild + redeploy to VM after a code change
rsync -az --delete --exclude='.git' --exclude='build/' --exclude='.venv' \
  --exclude='mcp/.venv' --exclude='sdk/python/.venv' \
  --exclude='testclient/.env.testclient' \
  <REPO_ROOT>/ root@<PROD_VM>:<REMOTE_REPO_DIR>/
ssh root@<PROD_VM> 'cd <REMOTE_REPO_DIR> && docker compose --profile prod build app && docker compose --profile prod up -d app'

# Verify paid endpoint
curl -I https://api.koreafilings.com/v1/disclosures/20260424900874/summary   # should 402

# Check production payment_log
ssh root@<PROD_VM> "cd <REMOTE_REPO_DIR> && docker compose exec -T postgres \
  psql -U dartintel -d dartintel -c 'SELECT id, rcpt_no_accessed, amount_usdc, facilitator_tx_id, settled_at FROM payment_log ORDER BY settled_at DESC LIMIT 10;'"

# PyPI re-upload (after `~/.pypirc` is set up)
cd sdk/python && rm -rf dist/ && python -m build && twine upload dist/*
cd ../../mcp       && rm -rf dist/ && python -m build && twine upload dist/*
```

## Key architectural reminders

- All of these are already enforced in code / CLAUDE.md — do not
  relitigate unless you have a strong reason.
- x402 settlement happens AFTER a successful 2xx, never before (prevents
  charging on 5xx). See `X402SettlementAdvice`.
- Payment signatures are de-duped in Redis via `SETNX` with 1-hour TTL.
  `afterCompletion` releases on non-2xx so retries work.
- All external HTTP clients use `JdkClientHttpConnector` — Reactor
  Netty's TLS negotiation fails against the DART portal.
- Resilience4j is wired per provider (`dart`, `gemini`, `facilitator`).
  `gemini` additionally has a RateLimiter tuned for free-tier 15 RPM.
- Summaries are generated once per `rcpt_no` and cached forever. LLM
  cost is paid by the first caller only; subsequent calls hit the
  Postgres cache and keep their margin.
- `.env` changes require a JVM restart — env is captured at boot.
