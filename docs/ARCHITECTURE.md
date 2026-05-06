# Architecture — Korea Filings

## High-Level View

The system is one Spring Boot 3.4 application running on a small
Linux VPS, fronted by Cloudflare Tunnel (no inbound ports), backed
by Redis 7 and PostgreSQL 16 in Docker Compose, calling out to
DART for source disclosures and to Gemini 2.5 Flash-Lite for
English summarisation. x402 payment verification and on-chain
settlement are outsourced to the Coinbase CDP facilitator. No
Kafka, no Kubernetes, no microservices — this is deliberately a
well-factored monolith that a single maintainer can operate.

```
                    ┌─────────────────────────┐
                    │     AI Agent / Client   │
                    └────────────┬────────────┘
                                 │ HTTPS
                                 ▼
                    ┌─────────────────────────┐
                    │     Cloudflare Edge     │  DDoS, HTTPS, IP hiding
                    │ (api.koreafilings.com)  │
                    └────────────┬────────────┘
                                 │ Tunnel (outbound only)
                                 ▼
          ┌──────────────────────────────────────────────┐
          │      Production VM (Docker Compose)          │
          │                                                │
          │  ┌──────────────────────────────────────┐    │
          │  │     Spring Boot 3.4 / Java 21        │    │
          │  │                                        │    │
          │  │   ┌────────────────────────────────┐  │    │
          │  │   │ X402PaywallInterceptor         │  │    │
          │  │   │ (preHandle: 402 / verify)      │  │    │
          │  │   │ X402SettlementAdvice           │  │    │
          │  │   │ (afterCompletion: settle)      │  │    │
          │  │   └────────────────┬───────────────┘  │    │
          │  │                    ▼                   │    │
          │  │   ┌────────────────────────────────┐  │    │
          │  │   │ Controllers                    │  │    │
          │  │   │  · DisclosuresController       │  │    │
          │  │   │  · CompanyController (free)    │  │    │
          │  │   │  · PublicController            │  │    │
          │  │   │  · WellKnownController         │  │    │
          │  │   └──────┬──────────┬──────────────┘  │    │
          │  │          ▼          ▼                  │    │
          │  │   ┌────────────┐ ┌────────────────┐   │    │
          │  │   │ DART       │ │ Summary        │   │    │
          │  │   │ Ingestor   │ │ Service        │   │    │
          │  │   │ (sched 30s)│ │ (job consumer) │   │    │
          │  │   │ Company    │ │ SummaryRetry   │   │    │
          │  │   │ Sync       │ │ Scheduler      │   │    │
          │  │   │ (09:30 KST)│ │                │   │    │
          │  │   └─────┬──────┘ └────────┬───────┘   │    │
          │  └─────────┼──────────────────┼──────────┘    │
          │            ▼                  ▼                │
          │      ┌──────────┐       ┌──────────┐           │
          │      │PostgreSQL│       │  Redis   │           │
          │      │   16     │       │    7     │           │
          │      └──────────┘       └──────────┘           │
          └────────────┬───────────────────┬───────────────┘
                       │                   │
        ┌──────────────┘                   └─────────────┐
        ▼                                                ▼
┌───────────────┐  ┌──────────────────┐  ┌─────────────────────────┐
│   DART Open   │  │  Gemini 2.5      │  │  Coinbase CDP           │
│      API      │  │  Flash-Lite API  │  │  Facilitator            │
│ (list +       │  │  (summarisation) │  │  (verify + settle,      │
│  corpCode)    │  │                  │  │   Ed25519 JWT auth)     │
└───────────────┘  └──────────────────┘  └─────────────────────────┘
```

## Module Structure

The monolith is organised into seven feature packages plus shared
config. Every external dependency (DART, Gemini, Coinbase) lives
behind a single `*Client` class with timeouts + Resilience4j
circuit-breakers, so the service code never touches raw HTTP.

```
com.dartintel.api
├── ApiApplication.java              — Spring Boot entrypoint
│
├── ingestion/                       — DART polling and raw persistence
│   ├── DartClient.java              — typed client (list.json + corpCode.xml)
│   ├── DartProperties.java          — @ConfigurationProperties for DART
│   ├── DartListResponse.java        — DTO for /list.json
│   ├── DartPollingScheduler.java    — @Scheduled(fixedDelay = 30s)
│   ├── Disclosure.java              — entity (with ticker col, V6)
│   └── DisclosureRepository.java    — JPA
│
├── company/                         — KRX listed-company directory (v1.1)
│   ├── Company.java                 — entity (ticker, corp_code, names, market)
│   ├── CompanyRepository.java       — pg_trgm fuzzy search via native query
│   ├── CompanyService.java          — XML parser for DART corpCode.xml dump
│   ├── CompanySyncScheduler.java    — bootstrap + daily 09:30 KST refresh
│   ├── CompanyController.java       — free /v1/companies endpoints
│   └── CompanyDto.java              — response shape
│
├── summarization/                   — LLM pipeline
│   ├── classifier/
│   │   └── ComplexityClassifier.java  — keyword-based on report_nm
│   ├── job/
│   │   ├── SummaryJobQueue.java     — Redis-backed queue (LPUSH/BRPOP)
│   │   ├── SummaryJobConsumer.java  — dedicated thread pool consumer
│   │   ├── SummaryRetryScheduler.java — re-enqueues failed jobs
│   │   └── BackfillRunner.java      — one-shot backfill on startup
│   ├── llm/
│   │   ├── LlmClient.java           — interface (summarize + modelId)
│   │   ├── GeminiFlashLiteClient.java — only impl today (v1.1)
│   │   └── GeminiProperties.java    — model + key + RPM
│   ├── SummaryService.java          — orchestrator
│   ├── SummaryWriter.java           — single-writer transactional persistence
│   ├── DisclosureContext.java       — input DTO into the LLM
│   ├── SummaryEnvelope.java         — raw LLM response, pre-validation
│   ├── SummaryResult.java           — validated value object
│   ├── DisclosureSummary.java       — entity
│   ├── DisclosureSummaryRepository.java
│   ├── LlmAudit.java                — entity (every LLM call)
│   └── LlmAuditRepository.java
│
├── payment/                         — x402 v2 middleware
│   ├── X402Paywall.java             — @interface (priceUsdc + pricingMode)
│   ├── X402PaywallInterceptor.java  — preHandle: emit 402 / verify
│   ├── X402SettlementAdvice.java    — afterCompletion: settle on 2xx; SQLState-based DataIntegrityViolation differentiation (23505 idempotent, others reconciliation-failure)
│   ├── X402Properties.java          — facilitator URL, network, asset, EIP-712 token name/version
│   ├── X402PropertiesValidator.java — @PostConstruct fail-fast on misconfig (zero-address, mainnet/testnet domain mismatch)
│   ├── FacilitatorClient.java       — Coinbase CDP HTTP client
│   ├── CdpJwtSigner.java            — Ed25519 JWT for CDP auth
│   ├── PaymentStore.java            — Redis SETNX replay protection
│   ├── PaymentNotifier.java         — opt-in Slack/Discord webhook on every settlement
│   ├── PaymentLogReconciliationMonitor.java — @Scheduled gauge + counter on /actuator/prometheus for payment_log silent-drop detection
│   ├── VerifiedPayment.java         — request-scoped after verify
│   ├── PaymentLog.java              — entity (V12 column widths)
│   ├── PaymentLogRepository.java    — repo + countNullTxOlderThan(cutoff) for the gauge
│   └── dto/
│       ├── PaymentRequirement.java
│       └── FacilitatorSettleResponse.java
│
├── api/                             — HTTP controllers
│   ├── DisclosuresController.java   — /v1/disclosures/* (recent free, by-ticker paid, summary paid)
│   ├── PublicController.java        — /v1/pricing (free, machine-readable price descriptor)
│   ├── WellKnownController.java     — /.well-known/x402 + /.well-known/agent.json (AWP 0.2)
│   ├── DiscoveryRootController.java — / 302 to landing + /favicon.ico 204
│   └── ApiExceptionHandler.java     — uniform 400 / 405 / 503 envelopes
│
├── observability/                   — REQ_AUDIT pipeline
│   ├── RequestAuditFilter.java      — OncePerRequestFilter; structured key=value log line
│   ├── RequestAudit.java            — entity
│   ├── RequestAuditRepository.java  — JPA + deleteByTsBefore for nightly prune
│   └── RequestAuditPersister.java   — async bounded-queue + batch INSERT
│
└── config/
    ├── OpenApiConfig.java           — springdoc with x402 security scheme
    └── WebConfig.java               — interceptor + advice registration
```

The MCP server lives in a separate Python package (`mcp/`) and the
Python SDK in `sdk/python/` — both call the public HTTPS API; neither
runs in-process with the Java app. See those READMEs for their own
internal layout.

## Data Model

### `disclosure` (raw, from DART)

- `rcpt_no` (PK) — DART receipt number, 14 digits
- `corp_code` — DART corporate code, 8 digits
- `corp_name` — Korean company name
- `corp_name_eng` — English name, nullable
- `report_nm` — filing type in Korean
- `flr_nm` — filer name
- `rcept_dt` — receipt date (yyyymmdd)
- `rm` — remarks (DART disclosure type flag)
- `ticker` — denormalised KRX ticker (5-7 alphanumeric, V6 + V7), nullable
  for delisted / foreign / non-corp filers
- `body` — TEXT, plain-text rendering of the per-filing
  `/api/document.xml` ZIP, capped at 20,000 chars by
  `DartDocumentParser` before insert (V13). NULL until the first
  paid call for the rcpt_no fetches and parses the body.
- `created_at`, `updated_at`

Index on `(rcept_dt DESC, rcpt_no DESC)` for `/v1/disclosures/recent`.
Partial index on `ticker WHERE ticker IS NOT NULL` for
`/v1/disclosures/by-ticker?ticker=…` (V6).

### `company` (KRX directory, v1.1)

- `ticker` (PK) — VARCHAR(7), 5-7 alphanumeric (relaxed in V7 for SPACs)
- `corp_code` (UNIQUE) — DART 8-digit corporate code
- `name_kr` — Korean company name (200 chars)
- `name_en` — English name, nullable (300 chars)
- `market` — KOSPI / KOSDAQ / KONEX
- `last_modified_at` — DART's modification date for the row
- `synced_at`, `updated_at`

GIN trigram indexes on `name_kr` and `name_en` (pg_trgm) so the
fuzzy `find_company` query is sub-50ms across 3,961 rows.

### `disclosure_summary` (generated)

- `rcpt_no` (PK, FK to disclosure)
- `summary_en` — paraphrased English ≤ 400 chars
- `importance_score` — 1–10
- `event_type` — canonical UPPER_SNAKE_CASE
- `sector_tags` — JSONB array
- `ticker_tags` — JSONB array
- `actionable_for` — JSONB array
- `model_used` — `"gemini-2.5-flash-lite"` (only model in production)
- `input_tokens`, `output_tokens`, `cost_usd`
- `generated_at`

### `llm_audit` (every LLM call, append-only)

`id`, `rcpt_no`, `model`, `prompt_hash`, `input_tokens`,
`output_tokens`, `latency_ms`, `cost_usd`, `success`,
`error_message`, `created_at`. Used for cost tracking and debugging
the rare bad summary.

### `payment_log` (every settled x402 payment)

`id`, `rcpt_no_accessed` (nullable for batch endpoints),
`endpoint VARCHAR(500)`, `amount_usdc`, `payer_address`,
`facilitator_tx_id VARCHAR(200)`, `signature_hash VARCHAR(96)`,
`settled_at`. The single source of truth for revenue. Used for
on-chain reconciliation and tax prep.

`signature_hash` is uniquely indexed (`uq_payment_log_sig`) — a
double-write for the same EIP-3009 nonce is the operational
definition of an idempotent retry, and the UNIQUE constraint is
how the settlement advice's idempotency guard short-circuits.

Column widths above are post-V11 (`signature_hash` 64 → 96 for
the `"nonce:" + 0x + 64-hex` replay-key shape) and post-V12
(`facilitator_tx_id` 80 → 200 to decouple from the facilitator's
tx-reference format, `endpoint` 200 → 500 for forward-compat with
multi-constraint query endpoints). All three widenings were a
direct response to the round-9 silent-drop class — column-too-
small produced SQLState 22001 which the previous handler swallowed
as if it were a UNIQUE-constraint duplicate. The
`X402SettlementAdvice` handler now inspects the JDBC SQLState
explicitly and only treats `23505` as idempotent.

### `request_audit` (every non-GET or 4xx/5xx request, opt-in)

`id`, `ts`, `method`, `path`, `status`, `ip` (CF-Connecting-IP),
`user_agent`, `query_keys` (sorted CSV of param **names**, never
values), `body_bytes`, `content_type`, `has_x_payment`,
`has_payment_sig` (booleans — header values never persisted).

Populated by `RequestAuditPersister` via an async bounded queue
when both `audit.requests.enabled` and `audit.requests.persist` are
true. 90-day retention via a nightly prune at 04:00 UTC.

The table powers the funnel / KPI / cohort SQL playbook in
[`docs/ANALYTICS.md`](ANALYTICS.md): per-day UA category breakdown,
discovery file probe trend, the 5-step funnel (discovery → 402 →
signed → settled), week-over-week cohort comparison for
post-release retrospectives, new-integration emergence detection,
stuck-loop diagnosis. Designed to survive log rotation (50 MB × 5
generations) so cohort comparisons across releases stay possible
beyond ~a week of busy traffic.

### Redis keys

- `summary:{rcptNo}` → JSON, TTL 30d (write-through cache)
- `payment_sig:{sha256_hash}` → "1", TTL 1h (replay protection)
- `summary_job_queue` → LIST, consumed by SummaryJobConsumer
- `dart_last_rcept_dt` → STRING, polling watermark

## Request Lifecycle — Paid endpoint, fixed price

`GET /v1/disclosures/summary?rcptNo=…` (annotated
`@X402Paywall(priceUsdc = "0.005")`):

1. Spring dispatches to `DisclosuresController#getSummary`.
2. `X402PaywallInterceptor#preHandle` reads `@X402Paywall` →
   fixed price 0.005 USDC.
3. Reads the `PAYMENT-SIGNATURE` header (v2 transport spec),
   falling back to the legacy `X-PAYMENT` header for 0.2.x SDK
   / MCP clients still in the wild.
   - Missing → emit 402 with base64-encoded `PaymentRequired` in
     the `PAYMENT-REQUIRED` response header (x402 v2 transport)
     plus a v1-compatible JSON copy in the body. Includes the
     `bazaar` extension declaring input/output schema (with the
     `rcptNo` query param marked required).
4. SHA-256 hash the payload, `paymentStore.registerIfAbsent(hash)`
   via Redis `SET payment_sig:{hash} 1 NX EX 3600`.
   - Already registered → 402 "signature reused".
   - Non-base64 / non-JSON header → **400** "Malformed payment
     payload" per x402 v2 transport-spec error table (release lock).
5. **Resource URL binding** — compare
   `paymentPayload.resource.url` to the actual request URL (path +
   query). On mismatch, release the Redis signature and return 402
   "Resource URL mismatch". This is the server-side enforcement
   that prevents a signature for the cheap fixed-price endpoint
   from being replayed against the per-result endpoint — the
   EIP-3009 signature itself binds amount/nonce/validity, not URL.
6. `facilitatorClient.verify(payload, requirements)` against the
   CDP facilitator (Ed25519 JWT auth).
   - Invalid → release the Redis signature, 402 with reason.
   - Valid → attach `VerifiedPayment` to request as an attribute.
7. Controller runs. Reads summary from Redis, falls back to
   Postgres, serialises.
8. `X402SettlementAdvice#beforeBodyWrite` fires before the
   response body lands on the wire.
   - 2xx + settle success → write base64 settlement proof to
     `PAYMENT-RESPONSE` (with `X-PAYMENT-RESPONSE` echoed for v1
     clients), append a row to `payment_log`.
   - 2xx + settle throws / `success: false` → **fail-close per
     x402 v2 spec**: status is rewritten to 402, the failure
     `SettlementResponse` is base64-encoded into `PAYMENT-RESPONSE`
     (with the `X-PAYMENT-RESPONSE` alias), and the body is replaced
     with `{}`. The original controller payload is dropped so a
     facilitator outage cannot leak paid data unpaid; the 402 status
     lets spec-aware clients recognise the outcome as "payment not
     captured, retry with a fresh signature" rather than misreading
     it as a generic server fault. The Redis signature lock is also
     explicitly released — the on-chain EIP-3009 nonce was not
     consumed, so the same signed authorisation is still
     cryptographically valid for the client's retry.
   - non-2xx → settlement skipped; `afterCompletion` releases the
     Redis signature so the client can retry without paying twice.

### Summarisation pipeline — single-flight lock

`SummaryService#summarize` runs a Redis SETNX lock keyed
`summary_inflight:{rcptNo}` around the LLM call so the standing
{@link SummaryJobConsumer}, the retry scheduler, and any ad-hoc
backfill cannot race on the same receipt number. A consumer that
loses the SETNX returns immediately without paying for a redundant
LLM run; the lock has a 2-minute TTL that exceeds the configured
Gemini read timeout so a stuck consumer cannot hold the slot
forever. An audit-success short-circuit (`existsByRcptNoAndSuccessTrue`)
additionally protects the partial-write recovery case: if a previous
run committed the audit row but the summary insert failed, the retry
scheduler re-enqueues the rcptNo, but `summarize` sees the audit
success and returns without re-paying — operators can heal the
missing summary row from the audit data offline.

## Request Lifecycle — Paid endpoint, per-result price

`GET /v1/disclosures/by-ticker?ticker=…&limit=N` (annotated
`@X402Paywall(priceUsdc = "0.005", pricingMode = PER_RESULT,
countQueryParam = "limit", defaultCount = 5, maxCount = 50)`):

Same as the fixed-price flow except step 2 computes the effective
price as `unitPrice × clamp(limit, 1, maxCount)`. The 402 response
declares the dynamic amount in `accepts[0].amount` so the agent
sees the exact charge before signing. The signed authorisation
covers the whole batch in one on-chain transferWithAuthorization.

## Request Lifecycle — Ingestion (metadata only)

Round-11 (2026-05-06) split ingestion from summarisation. The poller
no longer triggers an LLM call — every disclosure lands in Postgres
as metadata, and summary generation runs lazily on the first paid
call (see "Lazy summarisation" below).

1. `DartPollingScheduler#poll()` fires every 30 seconds.
2. Queries DART `/list.json` for filings with `rcept_dt >=
   dart_last_rcept_dt` (Redis watermark).
3. For each returned filing, `disclosureRepository.existsByRcptNo`.
   If new:
   - Persist raw filing (with denormalised `ticker` looked up
     from the `company` table at write time).
   - `body` column starts NULL. The body fetch is deferred to
     the first paid call.
4. Update `dart_last_rcept_dt` to the max `rcept_dt` seen.

The legacy `SummaryJobQueue` / `SummaryJobConsumer` /
`SummaryRetryScheduler` beans remain in code for offline backfill —
disabled by default via `summary.consumer.enabled=false` and
`summary.retry.enabled=false`. To re-enable them for a backfill
run, override the env vars and push rcpt_nos onto
`summary_job_queue` directly.

## Request Lifecycle — Lazy summarisation (first paid call)

When `GET /v1/disclosures/summary?rcptNo=…` (or
`…/by-ticker?ticker=…`) hits a `disclosure_summary` cache miss, the
controller drives the generation synchronously inside the request
thread. The Redis SETNX single-flight lock around `SummaryService`
guarantees exactly one LLM call per `rcptNo` even under concurrent
paid requests; second-comers either see the lock and exit, or hit
the freshly-populated cache by the time they re-query.

1. Cache lookup: `disclosureSummaryRepository.findById(rcptNo)`. If
   present, return immediately (warm path — typical 99%+ of paid
   traffic once a filing has been paid for once).
2. If missing, verify the disclosure metadata exists in our DB. If
   not, return 404 — the agent supplied an unknown `rcptNo` and
   settlement-on-2xx leaves them uncharged.
3. Call `SummaryService.summarize(rcptNo)`:
   - Acquire the `summary_inflight:{rcptNo}` Redis SETNX lock
     (2-minute TTL).
   - Fetch the body if `disclosure.body IS NULL`:
     `DartClient.fetchDocument(rcptNo)` returns the ZIP from
     `/api/document.xml`, `DartDocumentParser.parse(...)` strips
     HTML/XBRL via jsoup and caps at 20,000 chars. Persist via a
     short `REQUIRES_NEW` transaction so a later LLM failure
     doesn't erase the cached body.
   - All body-fetch failures (404 = filing not yet finalised, open
     `dart-document` breaker, parser failure, empty payload)
     degrade gracefully to title-only summarisation rather than
     killing the request.
   - Call `GeminiFlashLiteClient.summarize(ctx)` with the body
     when present (truncated to 12,000 chars in the prompt) or
     `null` for title-only.
   - Audit-row commits first (`REQUIRES_NEW`), summary row second
     — the existing audit-success short-circuit covers the
     partial-write recovery case unchanged.
4. Re-query the cache and serve the freshly-written summary. If
   generation failed (audit_failure row written), return 503 so
   `X402SettlementAdvice` does not charge the caller; the agent
   may retry without paying twice.

`CompanySyncScheduler` runs separately:

- Bootstrap (initialDelay 30s, off the main thread): fetches
  the ~6 MB `corpCode.xml` ZIP and upserts ~3,961 companies into
  the `company` table.
- Daily 09:30 KST refresh: picks up rebrandings, IPOs, market
  transitions.

## Failure Modes and Handling

- **DART API down** — Resilience4j circuit-breaker on the `dart`
  instance opens; scheduler logs and no-ops. Next cycle retries.
  Cache hits keep paid traffic working.
- **DART `/document.xml` down or filing not finalised** — the
  separate `dart-document` Resilience4j instance (breaker + retry
  + 30 rpm rate limiter) opens or returns 404, and
  `SummaryService.ensureBody` swallows the exception and degrades
  to title-only summarisation. The agent still gets a paid
  response; only the depth of the summary suffers, and the next
  paid call retries the body fetch automatically.
- **Gemini API down or rate-limited** — Resilience4j
  CircuitBreaker + RateLimiter (10 RPM, conservative below the
  free-tier 15 RPM ceiling) on the `gemini` instance gate
  outbound calls. `SummaryRetryScheduler` re-enqueues failed
  jobs. Cache hits short-circuit the whole pipeline so paying
  readers are unaffected; only the first caller for a given
  `rcpt_no` can hit a 429-driven slow path.
- **Facilitator down** — every paid call fails 503 with a clear
  error. Payments are not silently accepted. Status reflects
  this immediately. Free endpoints (`/v1/companies`,
  `/v1/disclosures/recent`, `/v1/pricing`) keep working.
- **Postgres down** — application exits; Docker Compose
  restarts it. Redis cache serves existing summaries during
  the outage.
- **Redis down** — application falls back to Postgres for
  summary reads. Replay protection is temporarily disabled
  (log warning, allow payments through facilitator
  verification alone).
- **Cloudflare Tunnel disconnects** — reconnects automatically
  when connectivity returns. Cloudflare returns 5xx to clients
  during the outage.
- **VM host down** — VPS provider SLA + Docker Compose restart
  policy bring the stack back. No multi-region failover for MVP.

## Deployment Model

Single `docker-compose.yml` on the production VM. Services:

- `app` — Spring Boot multi-stage build (eclipse-temurin:21).
  ARM64 native, deployed via `rsync` + `docker compose build app`
  on the host.
- `postgres` — PostgreSQL 16, volume-mounted data, daily
  pg_dump → encrypted file (post-launch ops follow-up).
- `redis` — Redis 7, ephemeral.
- `cloudflared` — Cloudflare Tunnel daemon, configured with a
  token from the Cloudflare dashboard. Outbound-only — no inbound
  firewall ports open on the VM.

No Kubernetes. No service mesh. No Istio. Keep it small and
boring.

## Scaling Assumptions

MVP expects peak ~10 RPS. A single JVM on a small Linux VPS
handles this comfortably — JVM heap is sized to leave room for
Postgres, Redis, and the OS on the same host. If sustained
traffic exceeds 100 RPS the path is to vertically scale the VPS
in-place before considering any horizontal split.

The DART polling rate (30s) is fixed by DART's recommendation
and is independent of client traffic. LLM call volume is bounded
by new disclosures per weekday (~870 average, range 600–1,100), so daily Gemini spend is
predictable and small regardless of how many clients hit cached
summaries.

## Round-11 deltas (lazy + body fetch, 2026-05-06)

Body-aware summarisation moved out of v1.2 and into v1.1 as part of
the lazy pivot. The relevant changes are already reflected in the
sections above; for orientation:

- `DartClient.fetchDocument(rcptNo)` issues a single GET to
  `/api/document.xml` with the `dart-document` Resilience4j
  instance (separate breaker / retry / 30 rpm rate limiter from
  `/list.json`). The raw JDK HTTP client buffers the ZIP via
  `BodyHandlers.ofByteArray()` to avoid Reactor Netty's data-buffer
  truncation issue; bodies above the configured cap (default 5 MB)
  are rejected at receipt time.
- `DartDocumentParser` walks the ZIP, keeps `.html` / `.htm` /
  `.xml` entries, runs each through jsoup `text()` to strip
  markup, normalises whitespace, and truncates at 20,000 chars.
  Image attachments (`.jpg`, `.png`) and other binaries are
  skipped without error.
- `disclosure.body` (TEXT, V13 migration) caches the parsed text.
  NULL = body has not been fetched yet. The summarisation path
  populates it on first paid call via a short `REQUIRES_NEW`
  transaction so a later LLM failure does not erase the cached
  body — the next attempt short-circuits the body fetch.
- The Gemini prompt branches on body presence: when the body is
  available, the model is instructed to lead with concrete
  numbers (amounts, dilution %, counterparty); otherwise it falls
  back to title-only summarisation with the previous behaviour.
  Prompt-side body cap is 12,000 chars (parser cap is 20,000)
  because Flash-Lite cost scales linearly with input tokens and
  the marginal quality benefit past 12K is small for analyst-style
  summaries.

A future tiered-pricing iteration (event-type clusters, e.g.
LOW/STANDARD/HIGH) is tracked in `docs/ROADMAP.md` but is not on
the v1.1 critical path. Current pricing is flat 0.005 USDC for
both `/summary` and `/by-ticker × limit`.
