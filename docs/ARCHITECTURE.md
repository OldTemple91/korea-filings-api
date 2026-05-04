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
│   ├── X402SettlementAdvice.java    — afterCompletion: settle on 2xx
│   ├── X402Properties.java          — facilitator URL, network, asset
│   ├── FacilitatorClient.java       — Coinbase CDP HTTP client
│   ├── CdpJwtSigner.java            — Ed25519 JWT for CDP auth
│   ├── PaymentStore.java            — Redis SETNX replay protection
│   ├── VerifiedPayment.java         — request-scoped after verify
│   ├── PaymentLog.java              — entity
│   ├── PaymentLogRepository.java
│   └── dto/
│       ├── PaymentRequirement.java
│       └── FacilitatorSettleResponse.java
│
├── api/                             — HTTP controllers
│   ├── DisclosuresController.java   — /v1/disclosures/* (recent free, by-ticker paid, summary paid)
│   ├── PublicController.java        — /v1/pricing (free, machine-readable price descriptor)
│   └── WellKnownController.java     — /.well-known/x402 (agent discovery)
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
`endpoint`, `amount_usdc`, `payer_address`, `facilitator_tx_id`,
`settled_at`. The single source of truth for revenue. Used for
on-chain reconciliation and tax prep.

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
5. `facilitatorClient.verify(payload, requirements)` against the
   CDP facilitator (Ed25519 JWT auth).
   - Invalid → release the Redis signature, 402 with reason.
   - Valid → attach `VerifiedPayment` to request as an attribute.
6. Controller runs. Reads summary from Redis, falls back to
   Postgres, serialises.
7. `X402SettlementAdvice#beforeBodyWrite` fires before the
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
     it as a generic server fault.
   - non-2xx → settlement skipped; `afterCompletion` releases the
     Redis signature so the client can retry without paying twice.

## Request Lifecycle — Paid endpoint, per-result price

`GET /v1/disclosures/by-ticker?ticker=…&limit=N` (annotated
`@X402Paywall(priceUsdc = "0.005", pricingMode = PER_RESULT,
countQueryParam = "limit", defaultCount = 5, maxCount = 50)`):

Same as the fixed-price flow except step 2 computes the effective
price as `unitPrice × clamp(limit, 1, maxCount)`. The 402 response
declares the dynamic amount in `accepts[0].amount` so the agent
sees the exact charge before signing. The signed authorisation
covers the whole batch in one on-chain transferWithAuthorization.

## Request Lifecycle — Ingestion

1. `DartPollingScheduler#poll()` fires every 30 seconds.
2. Queries DART `/list.json` for filings with `rcept_dt >=
   dart_last_rcept_dt` (Redis watermark).
3. For each returned filing, `disclosureRepository.existsByRcptNo`.
   If new:
   - Persist raw filing (with denormalised `ticker` looked up
     from the `company` table at write time).
   - `summaryJobQueue.push(rcptNo)`.
4. Update `dart_last_rcept_dt` to the max `rcept_dt` seen.
5. `SummaryJobConsumer` (separate thread) runs `BRPOP
   summary_job_queue 10`.
6. For each pulled `rcptNo`: classify complexity (informational
   only — does not change the LLM model today), call
   `GeminiFlashLiteClient`, validate JSON, persist summary,
   write `llm_audit`, populate Redis.
7. On exception, `SummaryRetryScheduler` re-enqueues the job on
   a separate cadence so a transient Gemini outage does not lose
   filings.

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

## v1.2 — planned shape (not yet implemented)

The v1.2 milestone introduces deep filing analysis. The
deltas to this architecture are:

- New `DartClient.fetchDocument(rcptNo)` pulling the per-filing
  ZIP from `/api/document.xml`. Cache the unzipped body on
  Postgres next to the existing summary so downstream LLM runs
  are idempotent.
- New body-extraction stage between ingestion and summarisation
  for the six highest-value event types (RIGHTS_OFFERING,
  CONVERTIBLE_BOND_ISSUANCE, DEBT_ISSUANCE, ACQUISITION,
  SUPPLY_CONTRACT_SIGNED, MAJOR_SHAREHOLDER_FILING). Templated
  XBRL parsers extract amount, dilution %, counterparty, dates.
- New `SummaryResult.keyFacts` field carrying the extracted
  numbers as a structured JSON blob.
- New paid endpoint `/v1/disclosures/deep?rcptNo=…` priced at
  ~0.020 USDC. Existing endpoints stay metadata-only at 0.005
  USDC so callers pick depth at call time.
- New `dart` rate-limiter for the `/document.xml` endpoint
  (separate per-key budget from `/list.json`).

See `docs/ROADMAP.md#v12--deep-filing-analysis-planned` for the
full plan and ship trigger.
