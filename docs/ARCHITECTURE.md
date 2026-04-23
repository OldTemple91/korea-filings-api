# Architecture — DART Intelligence API

## High-Level View

The system is one Spring Boot application running on a home server, fronted by Cloudflare Tunnel, backed by Redis and PostgreSQL in Docker Compose, calling out to DART for source data and to Gemini/OpenAI for summaries. x402 payment verification is outsourced to the Coinbase facilitator. No Kafka, no Kubernetes, no microservices — this is deliberately a well-factored monolith that one person can operate on weekends.

```
                    ┌─────────────────────────┐
                    │     AI Agent / Client   │
                    └────────────┬────────────┘
                                 │ HTTPS
                                 ▼
                    ┌─────────────────────────┐
                    │     Cloudflare Edge     │  DDoS, HTTPS, IP hiding
                    │    (api.{domain}.tld)   │
                    └────────────┬────────────┘
                                 │ Tunnel (outbound from home)
                                 ▼
          ┌──────────────────────────────────────────────┐
          │          Home Server (Docker Compose)         │
          │                                                │
          │  ┌──────────────────────────────────────┐    │
          │  │     Spring Boot Application          │    │
          │  │                                        │    │
          │  │   ┌────────────────────────────────┐  │    │
          │  │   │   X402PaywallInterceptor       │  │    │
          │  │   │   (pre-handle + post-handle)   │  │    │
          │  │   └────────────────┬───────────────┘  │    │
          │  │                    ▼                   │    │
          │  │   ┌────────────────────────────────┐  │    │
          │  │   │   Disclosure Controllers       │  │    │
          │  │   └──────┬──────────┬──────────────┘  │    │
          │  │          ▼          ▼                  │    │
          │  │   ┌────────────┐ ┌────────────────┐   │    │
          │  │   │ DART       │ │ Summary        │   │    │
          │  │   │ Ingestor   │ │ Service        │   │    │
          │  │   │ (sched)    │ │ (job consumer) │   │    │
          │  │   └─────┬──────┘ └────────┬───────┘   │    │
          │  └─────────┼──────────────────┼──────────┘    │
          │            ▼                  ▼                │
          │      ┌──────────┐       ┌──────────┐           │
          │      │PostgreSQL│       │  Redis   │           │
          │      └──────────┘       └──────────┘           │
          └────────────┬───────────────────┬───────────────┘
                       │                   │
        ┌──────────────┘                   └─────────────┐
        ▼                                                ▼
┌───────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   DART Open   │  │  Gemini API  │  │  OpenAI API  │  │  Coinbase    │
│      API      │  │   (primary)  │  │  (fallback)  │  │  Facilitator │
└───────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
```

## Module Structure

```
com.dartintel.api
├── ApiApplication.java            — Spring Boot entrypoint
│
├── ingestion/                     — DART polling and raw persistence
│   ├── DartClient.java            — Typed client for opendart.fss.or.kr
│   ├── DartPollingScheduler.java  — @Scheduled(fixedDelay = 30000)
│   ├── DisclosureRepository.java  — JPA
│   └── Disclosure.java            — entity
│
├── summarization/                 — LLM pipeline
│   ├── job/
│   │   ├── SummaryJobQueue.java   — Redis-backed queue (List LPUSH/BRPOP)
│   │   └── SummaryJobConsumer.java
│   ├── classifier/
│   │   └── ComplexityClassifier.java
│   ├── llm/
│   │   ├── LlmClient.java         — interface
│   │   ├── GeminiFlashLiteClient.java
│   │   ├── GeminiFlashClient.java
│   │   └── OpenAiNanoClient.java  — fallback
│   ├── SummaryService.java        — orchestrator
│   ├── DisclosureSummary.java     — entity
│   └── DisclosureSummaryRepository.java
│
├── payment/                       — x402 middleware
│   ├── X402PaywallInterceptor.java
│   ├── X402Paywall.java           — @interface (method annotation)
│   ├── FacilitatorClient.java     — Coinbase HTTP client
│   ├── PaymentVerifier.java
│   ├── PaymentStore.java          — Redis SETNX replay protection
│   └── dto/
│       ├── PaymentRequirements.java
│       └── PaymentProof.java
│
├── pricing/
│   ├── PricingPolicy.java         — endpoint → price map
│   └── PricingRepository.java     — Postgres-backed from week 3
│
├── api/                           — HTTP controllers
│   ├── DisclosuresController.java
│   ├── PublicController.java      — health, /v1/pricing (free)
│   └── dto/
│       ├── DisclosureSummaryDto.java
│       └── ErrorResponse.java
│
├── mcp/                           — MCP server wrapper (week 5)
│   └── McpAdapter.java
│
├── config/
│   ├── RedisConfig.java
│   ├── WebClientConfig.java
│   ├── OpenApiConfig.java
│   └── ResilienceConfig.java      — Resilience4j CircuitBreakers
│
└── common/
    ├── exception/
    └── logging/
```

## Data Model

### `disclosure` (raw, from DART)

- `rcpt_no` (PK) — DART receipt number, 14 digits
- `corp_code` — DART corporate code, 8 digits
- `corp_name` — Korean company name
- `corp_name_eng` — English name, nullable (enriched from corp_code master file)
- `report_nm` — filing type in Korean
- `flr_nm` — filer name
- `rcept_dt` — receipt date (yyyymmdd)
- `rm` — remarks (may contain disclosure type flag)
- `created_at`, `updated_at`

Index on `(rcept_dt DESC, rcpt_no DESC)` for `/latest` queries.
Index on `corp_code` for `/by-ticker` queries.

### `disclosure_summary` (generated)

- `rcpt_no` (PK, FK to disclosure)
- `summary_en` — AI-generated English summary
- `importance_score` — 1–10
- `event_type` — canonical string (MERGER, RIGHTS_OFFERING, DIVIDEND_DECISION, …)
- `sector_tags` — JSONB array
- `ticker_tags` — JSONB array
- `actionable_for` — JSONB array
- `model_used` — "gemini-2.5-flash-lite" etc.
- `input_tokens`, `output_tokens`, `cost_usd` — for audit
- `generated_at`

### `llm_audit` (every LLM call)

Append-only. Used for cost tracking and debugging bad summaries.

- `id`, `rcpt_no`, `model`, `prompt_hash`, `input_tokens`, `output_tokens`, `latency_ms`, `cost_usd`, `success`, `error_message`, `created_at`

### `payment_log` (every settled x402 payment)

- `id`, `rcpt_no_accessed` (nullable, depends on endpoint), `endpoint`, `amount_usdc`, `payer_address`, `facilitator_tx_id`, `settled_at`

Used for revenue reporting and tax prep.

### Redis keys

- `summary:{rcptNo}` → JSON, TTL 30d
- `payment_sig:{sha256_hash}` → "1", TTL 1h (replay protection)
- `summary_job_queue` → LIST, consumed by SummaryJobConsumer
- `dart_last_rcept_dt` → STRING, used to limit polling window

## Request Lifecycle — Paid Endpoint

Given `GET /v1/disclosures/{rcptNo}/summary`:

1. Spring dispatches to `DisclosuresController#getSummary` via `RequestMappingHandlerMapping`.
2. `X402PaywallInterceptor#preHandle` fires first.
3. Interceptor reads `@X402Paywall` annotation on the method. Gets `priceUsdc = "0.005"`.
4. Reads `X-PAYMENT` header.
   - Missing → write 402 response with `PaymentRequirements` body, return `false`.
5. Hash the `X-PAYMENT` value (SHA-256).
6. `paymentStore.registerIfAbsent(hash)` — Redis `SET payment_sig:{hash} 1 NX EX 3600`.
   - Already exists → 402 "signature reused", return `false`.
7. `facilitatorClient.verify(paymentProof, priceUsdc, network="base")`.
   - Invalid → release from Redis, 402 with error, return `false`.
   - Valid → attach `VerifiedPayment` to request attribute, return `true`.
8. Controller executes. Reads summary from Redis (`summary:{rcptNo}`), falls back to Postgres, serializes to `DisclosureSummaryDto`.
9. `X402PaywallInterceptor#postHandle` fires.
10. If response status is 2xx: `facilitatorClient.settle(verifiedPayment)`, write settlement proof to `X-PAYMENT-RESPONSE` header, append to `payment_log`.
11. If response is 5xx or 4xx (after auth): release Redis signature (client may retry without double-paying); do not settle.

## Request Lifecycle — Ingestion

1. `DartPollingScheduler#poll()` fires every 30 seconds.
2. Queries DART `/list.json` for filings with `rcept_dt >= dart_last_rcept_dt` (from Redis).
3. For each returned filing, `disclosureRepository.existsByRcptNo(...)`. If false:
    - Persist raw filing.
    - `summaryJobQueue.push(rcptNo)`.
4. Update `dart_last_rcept_dt` to the max `rcept_dt` seen.
5. `SummaryJobConsumer` (separate thread) runs `BRPOP summary_job_queue 10`.
6. For each pulled `rcptNo`: classify complexity, call LLM, persist summary, populate Redis.

## Failure Modes and Handling

- **DART API down** — scheduler logs and no-ops. Next cycle retries.
- **LLM API down** — summary job retries with fallback model. After 3 failures, persist a placeholder summary (`summaryEn: "Summary unavailable. Please retry later."`, `importanceScore: 0`) and mark the row for reprocessing.
- **Facilitator down** — every paid call fails with 503 and a clear error. Payments are not silently accepted. Status page reflects this immediately.
- **Postgres down** — application exits; Docker Compose restarts it. Redis cache serves existing summaries during the outage.
- **Redis down** — application falls back to Postgres for summary reads. Replay protection is temporarily disabled (log warning, allow payments through facilitator verification alone).
- **Home internet down** — Cloudflare Tunnel reconnects automatically when connectivity returns. Cloudflare returns 522 to clients during outage.
- **Home power loss** — UPS gives ~10 minutes for graceful shutdown via `systemd-shutdown`. Docker Compose on boot brings the stack back.

## Deployment Model

Single `docker-compose.yml` on the home server. Services:

- `app` — Spring Boot (JAR built in CI, pulled from GHCR)
- `postgres` — PostgreSQL 16, volume-mounted data
- `redis` — Redis 7
- `cloudflared` — Cloudflare Tunnel daemon, configured with a token from the Cloudflare dashboard

No Kubernetes. No service mesh. No Istio. Keep it small and boring.

## Scaling Assumptions

MVP expects peak ~10 RPS. A single JVM on a modest home server (small VPS, 16 GB RAM) handles this with room to spare. If traffic ever exceeds 100 RPS sustained, consider moving to Fly.io or a VPS — but that is a good problem to have.

The DART polling rate (30s) is fixed by DART's recommendation and is independent of client traffic.

LLM call volume is bounded by the number of new disclosures per day (~300–500), so daily LLM spend is predictable and small regardless of client traffic.
