# Service Level Objectives (v0.5)

What we promise the service does. Every number here is committed to and measurable. When a number is broken for a sustained window, the operator opens an incident.

These targets are intentionally narrower than industry-leading paid APIs because the service runs on a single Linux VPS by design (CLAUDE.md locks the deployment shape). They are wide enough to be achievable without architectural change at MVP scale.

## Targets

| Metric | Target | How measured | Source of truth |
|---|---|---|---|
| Monthly availability (paid endpoints) | **99.0%** | `(1 - sum(http_server_requests_seconds_count{uri=~"/v1/disclosures/.*",status=~"5.."}) / sum(http_server_requests_seconds_count{uri=~"/v1/disclosures/.*"}))` over 30d | `/actuator/prometheus` |
| Cached paid summary p95 latency | **≤ 300 ms** | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/v1/disclosures/summary",status="200"}[5m])))` | `/actuator/prometheus` |
| Free endpoint p95 latency | **≤ 100 ms** | same query, `uri="/v1/companies"` and `uri="/v1/disclosures/recent"` | `/actuator/prometheus` |
| Cold (uncached) summary latency | **≤ 12 s p95** | `time(SummaryService.summarize)` Micrometer timer | `/actuator/prometheus` |
| 5xx error rate | **< 0.5% / day** | `sum(... status=~"5..") / sum(...)` excluding `/actuator/*` | `/actuator/prometheus` |
| `payment_log` reconciliation gap | **= 0 rows** | `payment_log_reconciliation_gap_rows` gauge (per-minute scan with 5-min grace window) + `payment_log_reconciliation_failures_total` counter | `/actuator/prometheus` |

## Why these numbers

**99.0% availability** is one nine — about 7h 18min of allowed downtime per month. Higher would commit to multi-instance / multi-region operation that this MVP doesn't support. Lower would be unprofessional for a paid service.

**300 ms cached p95** is the right number for a single-region (Europe) deployment serving global agents. The PRD originally targeted 200 ms; adding two facilitator round-trips per paid call (verify + settle) at ~50 ms each pushes the floor up. Once the settlement path is asynchronised (capacity audit P0), tightening to 200 ms is realistic.

**12 s cold summary** covers the round-11 lazy-generation path: DART `/document.xml` body fetch (~2-5 s) + jsoup parse (~50 ms) + Gemini call (~5-7 s) + write-back. The body fetch can fall through to title-only on a 404 / open `dart-document` breaker — that path is faster (~6 s) but lower quality. The cache hit ratio is the real lever — every paid call after the first for the same `rcptNo` hits the cache and lands well under the 300 ms p95.

**0 rows reconciliation gap** is the strictest target. Every settled payment must produce a `payment_log` row with a non-null `facilitator_tx_id`. The round-9 audit found a buried regression of exactly this class (column-too-small swallowed as if it were an idempotent duplicate); the round-10 fix layered five defences against recurrence: V11 + V12 column widening, SQLState-based handler differentiation, the `PaymentLogReconciliationMonitor` Prometheus gauge above (per-minute scan with 5 min grace for in-flight rows), the `_failures_total` counter incremented from the integrity-violation / DB-down branches, and a wiring test that exercises all five persistence outcomes. RUNBOOK incident scenario 12 documents the manual backfill procedure when the alert fires. Detection now survives a mis-configured `X402_NOTIFY_WEBHOOK_URL` — Grafana can alert on either Prometheus signal independently of the webhook path.

## Error budget

Monthly error budget: 1.0% × 30 days = 7h 18m of allowed downtime per month.

When > 50% of the budget is burned in a rolling 7-day window, the operator pauses non-trivial deploys and prioritises stability work. When 100% is burned, the operator publishes an incident report and considers a one-month feature freeze.

There is no SLO penalty paid out to customers — there are no commercial customers as of v0.5. The budget exists to govern operator behaviour, not user compensation.

## What we don't promise

- **Per-call response correctness** — we promise a 200 means we paid the LLM, not that the LLM was right. Summaries can be wrong.
- **Real-time settlement** — the on-chain settle round-trip is part of the response time. A facilitator outage is a 402, not a 5xx, and it doesn't burn the SLO budget.
- **Multi-region latency** — a request from Asia to a European VPS pays ~200 ms baseline. Documented in [ARCHITECTURE.md](ARCHITECTURE.md).
- **Backwards compat with v1 transport headers** beyond v0.5.x — `X-PAYMENT` / `X-PAYMENT-RESPONSE` are accepted today; deprecation timeline TBD once 0.3.x SDK adoption is observable.

## How the operator monitors this

- `/actuator/prometheus` is exposed (round-6 audit). Point a Grafana Cloud free-tier scrape at it; alert on each metric crossing its threshold.
- `PaymentNotifier` Slack/Discord webhook fires per settled payment — set `X402_NOTIFY_WEBHOOK_URL` in `.env` to enable.
- Daily / weekly checklist in [RUNBOOK.md](RUNBOOK.md#daily--weekly-checks).
