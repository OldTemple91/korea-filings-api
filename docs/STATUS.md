# STATUS — where we left off (2026-04-24)

Read this first when picking up on a different machine. Summarises what is
live, what's next, and the minimum setup to keep moving.

## TL;DR

- **Weeks 1–5 complete.** Ingestion, summarisation, x402 paywall, public
  deployment, landing page, Python SDK, MCP server, OpenAPI docs — all
  live in production at `api.koreafilings.com`.
- **10 on-chain x402 settlements** executed against Base Sepolia
  (curl + SDK + MCP regression suite). All recorded in `payment_log`.
- **Mainnet live since 2026-04-28**: first on-chain settlement at
  [`0x681c995e…`](https://basescan.org/tx/0x681c995e149d3ce5765ea8a3b0f921a45352fccefbd9fc9258bf4f6141eafd7c).
  Facilitator: Coinbase CDP (Ed25519 JWT auth). Bug caught at flip
  time: EIP-712 domain `name` was hard-coded to "USDC" but the Base
  mainnet contract returns "USD Coin"; fixed via the new
  `X402_TOKEN_NAME` / `X402_TOKEN_VERSION` config knobs and committed
  in b7af24e.
- **PyPI packages published** — `koreafilings` 0.1.0 + `koreafilings-mcp`
  0.1.0. Names permanently reserved.
- **PyPI packages published** — `koreafilings` 0.1.0 + `koreafilings-mcp` 0.1.0.
- **Directory registrations** — x402scan + Glama + mcp.so done; Smithery deferred.
- **Next**: HN Show HN post on the next available Tue/Wed at 22:00 KST.

## What's live

| surface | URL | notes |
|---|---|---|
| Landing page | https://koreafilings.com | Cloudflare Workers + static assets. Source in `landing/index.html`. |
| API root | https://api.koreafilings.com | Spring Boot on production VM via Cloudflare Tunnel. |
| Swagger UI | https://api.koreafilings.com/swagger-ui/index.html | Human-readable API docs. |
| OpenAPI spec | https://api.koreafilings.com/v3/api-docs | Machine-readable JSON. |
| Pricing | https://api.koreafilings.com/v1/pricing | Free, lists paid endpoints + x402 wallet. |
| Health | https://api.koreafilings.com/actuator/health | Liveness/readiness. |
| Paid summary | `GET /v1/disclosures/{rcptNo}/summary` | 0.005 USDC on Base mainnet via Coinbase CDP facilitator. |
| Python SDK | https://pypi.org/project/koreafilings/ | `pip install koreafilings` |
| MCP server | https://pypi.org/project/koreafilings-mcp/ | `uv tool install koreafilings-mcp` |
| Source | https://github.com/OldTemple91/korea-filings-api | Private push via `OldTemple91`. |

## Infrastructure

- **production VM** — VPS ARM, 2 vCPU / 4GB / 40GB, ***.
  Public IP `<PROD_VM>`. Ubuntu 24.04. SSH as root with key. App
  runs under `docker compose --profile prod` at `/root/korea-filings-api/`.
- **Cloudflare** — DNS, Tunnel (connector running on VM, outbound-only,
  no inbound ports), Workers hosting `koreafilings.com`. Zone on
  `kianchau.ns.cloudflare.com / michelle.ns.cloudflare.com`.
- **PyPI** — both packages under account that owns
  `***`.
- **GitHub** — `OldTemple91/korea-filings-api`. Push access via stored
  HTTPS creds on this Mac.

## What's left

1. **v2 prompt backfill** — the v1 prompt produced too many "OTHER"
   eventTypes and generic boilerplate; the v2 prompt
   (taxonomy + importance anchors + audience rules + temperature 0)
   is shipped and a handful of natural-poll filings are already
   summarised under it. The full re-summarisation of the existing
   720 disclosures is blocked on Gemini's free-tier daily quota
   resetting at 16:00 KST; trigger via:
   ```bash
   ssh root@<PROD_VM> "cd /root/korea-filings-api && \
     SUMMARY_BACKFILL_ENABLED=true \
     docker compose --profile prod up -d --force-recreate app"
   ```

2. **Directory registrations**: x402scan ✅, Glama ✅, mcp.so ✅,
   Smithery deferred (site outage at submission time, retry).

3. **HN Show HN post** — `docs/launch/HN_DRAFT.md` is the copy. Ideal
   timing: Tuesday/Wednesday 13:00–15:00 UTC (22:00–24:00 KST). The
   first mainnet settlement at
   [`0x681c995e…`](https://basescan.org/tx/0x681c995e149d3ce5765ea8a3b0f921a45352fccefbd9fc9258bf4f6141eafd7c)
   is the headline proof.

4. **Operational follow-ups (post-launch)**: Slack / email alert when
   `payment_log` gets a new row, Grafana dashboard for the four
   metrics that matter (calls/min, cache hit ratio, mean LLM cost
   per cache miss, payer diversity), TypeScript SDK port.

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
- production VM keeps its own copy of `/root/korea-filings-api/.env` —
  rsync from the laptop if the contents diverge.

## Commands cheat sheet

```bash
# Rebuild + redeploy to VM after a code change
rsync -az --delete --exclude='.git' --exclude='build/' --exclude='.venv' \
  --exclude='mcp/.venv' --exclude='sdk/python/.venv' \
  --exclude='testclient/.env.testclient' \
  <LOCAL_REPO>/ root@<PROD_VM>:/root/korea-filings-api/
ssh root@<PROD_VM> 'cd /root/korea-filings-api && docker compose --profile prod build app && docker compose --profile prod up -d app'

# Verify paid endpoint
curl -I https://api.koreafilings.com/v1/disclosures/20260424900874/summary   # should 402

# Check production payment_log
ssh root@<PROD_VM> "cd /root/korea-filings-api && docker compose exec -T postgres \
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
