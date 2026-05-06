# STATUS — where we left off (2026-05-06, post-round-10)

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
- **Round-11 lazy + body fetch (in flight, 2026-05-06).** Eager LLM
  calls at ingestion replaced with synchronous lazy generation on the
  first paid call, plus per-filing body fetch via DART's `/document.xml`
  ZIP. Summaries are now generated from filing body text (capped at
  20,000 chars by the parser, 12,000 in the prompt) instead of title
  metadata only — fixes the "details are in the filing body" filler
  that was eating ~50% of summary length on quantitative events. New
  `dart-document` Resilience4j circuit / retry / rate-limiter (30
  rpm) keeps the body endpoint isolated from `/list.json` polling.
  `disclosure.body` TEXT column added via V13. Test count 158 → 177
  green. ROADMAP v1.2 collapsed into v1.1 — the body-fetch tier no
  longer needs its own price tier because every paid call now uses
  body when available, falling back to title-only on 404 / open
  breaker / parse failure.
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
   get Korean filings as English signals", with v1.2 deep analysis
   explicitly named as the next phase so the launch positions a
   roadmap, not a demo.

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
