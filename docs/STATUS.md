# STATUS — where we left off (2026-04-24)

Read this first when picking up on a different machine. Summarises what is
live, what's next, and the minimum setup to keep moving.

## TL;DR

- **Weeks 1–5 complete.** Ingestion, summarisation, x402 paywall, public
  deployment, landing page, Python SDK, MCP server, OpenAPI docs — all
  live in production at `api.koreafilings.com`.
- **4 on-chain x402 settlements** executed against Base Sepolia (manual
  curl + SDK live test + SDK debug + MCP tool). All recorded in
  `payment_log`.
- **PyPI packages published** — `koreafilings` 0.1.0 + `koreafilings-mcp`
  0.1.0. Names permanently reserved.
- **Next (Week 6-2): Base mainnet switch.** Testnet → real USDC.
- After mainnet: directory registrations → HN Show HN.

## What's live

| surface | URL | notes |
|---|---|---|
| Landing page | https://koreafilings.com | Cloudflare Workers + static assets. Source in `landing/index.html`. |
| API root | https://api.koreafilings.com | Spring Boot on production VM via Cloudflare Tunnel. |
| Swagger UI | https://api.koreafilings.com/swagger-ui/index.html | Human-readable API docs. |
| OpenAPI spec | https://api.koreafilings.com/v3/api-docs | Machine-readable JSON. |
| Pricing | https://api.koreafilings.com/v1/pricing | Free, lists paid endpoints + x402 wallet. |
| Health | https://api.koreafilings.com/actuator/health | Liveness/readiness. |
| Paid summary | `GET /v1/disclosures/{rcptNo}/summary` | 0.005 USDC on Base Sepolia (currently). |
| Python SDK | https://pypi.org/project/koreafilings/ | `pip install koreafilings` |
| MCP server | https://pypi.org/project/koreafilings-mcp/ | `uv tool install koreafilings-mcp` |
| Source | https://github.com/OldTemple91/korea-filings-api | Private push via `OldTemple91`. |

## Infrastructure

- **production VM** — VPS ARM, 2 vCPU / 4GB / 40GB, ***.
  Public IP `178.104.207.3`. Ubuntu 24.04. SSH as root with key. App
  runs under `docker compose --profile prod` at `/root/korea-filings-api/`.
- **Cloudflare** — DNS, Tunnel (connector running on VM, outbound-only,
  no inbound ports), Workers hosting `koreafilings.com`. Zone on
  `kianchau.ns.cloudflare.com / michelle.ns.cloudflare.com`.
- **PyPI** — both packages under account that owns
  `***`.
- **GitHub** — `OldTemple91/korea-filings-api`. Push access via stored
  HTTPS creds on this Mac.

## What Week 6 still has

1. **Base mainnet switch** (next up).
   - Flip `.env`: uncomment the mainnet block, comment out the testnet
     block. Three vars change (`X402_FACILITATOR_URL`,
     `X402_NETWORK=eip155:8453`, `X402_ASSET=0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913`).
   - Get a Coinbase CDP API key from https://portal.cdp.coinbase.com/access/api
     — mainnet facilitator requires Bearer auth (testnet `www.x402.org`
     did not).
   - Add a WebClient filter in `FacilitatorClient` that injects the CDP
     key as `Authorization: Bearer <key>` on every call.
   - Fund the recipient wallet `0x8467Be164C75824246CFd0fCa8E7F7009fB8f720`
     with a small amount of real Base ETH (gas is paid by facilitator but
     some interactions may still need native).
   - Fund a payer wallet with real USDC on Base mainnet (~$2 is enough
     to run a handful of test calls).
   - Run a single live test via the `koreafilings` SDK with `network="base"`.
   - Update the landing page hero to say *"Live on Base mainnet"* if it
     currently claims Sepolia.

2. **Directory registrations** — x402scan, Agent.market, MCP registries
   (glama.ai, smithery.ai, mcp.so). Each takes 5–15 min; need name,
   description, GitHub link, demo video or JSON schema.

3. **HN Show HN post** — draft in a new `docs/launch/HN_DRAFT.md`.
   Ideal timing: Tuesday/Wednesday 13:00–15:00 UTC.

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
  <LOCAL_REPO>/ root@178.104.207.3:/root/korea-filings-api/
ssh root@178.104.207.3 'cd /root/korea-filings-api && docker compose --profile prod build app && docker compose --profile prod up -d app'

# Verify paid endpoint
curl -I https://api.koreafilings.com/v1/disclosures/20260424900874/summary   # should 402

# Check production payment_log
ssh root@178.104.207.3 "cd /root/korea-filings-api && docker compose exec -T postgres \
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
