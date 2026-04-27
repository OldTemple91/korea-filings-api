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
  Public IP `<PROD_VM>`. Ubuntu 24.04. SSH as root with key. App
  runs under `docker compose --profile prod` at `/root/korea-filings-api/`.
- **Cloudflare** — DNS, Tunnel (connector running on VM, outbound-only,
  no inbound ports), Workers hosting `koreafilings.com`. Zone on
  `kianchau.ns.cloudflare.com / michelle.ns.cloudflare.com`.
- **PyPI** — both packages under account that owns
  `***`.
- **GitHub** — `OldTemple91/korea-filings-api`. Push access via stored
  HTTPS creds on this Mac.

## What Week 6 still has

1. **Base mainnet switch — code shipped, switch deferred.**
   - All Java + config plumbing is in: `CdpJwtSigner` (Ed25519 JWT per
     request, jjwt 0.12), `X402Properties.Cdp`, `FacilitatorClient` filter
     that attaches `Authorization: Bearer <jwt>` only when the CDP fields
     are populated. 3 unit tests cover the signer.
   - Flipping `.env` to mainnet booted cleanly in production
     (CDP signer initialised, pricing endpoint reflected mainnet
     contract + chain id), but **no live on-chain mainnet settlement
     was executed** — Coinbase Korea does not surface USDC for
     small-amount card purchase, and the user opted not to KYC through
     a secondary exchange (Bybit/MEXC) just for a smoke test.
   - **Decision: launch on testnet**, keep the mainnet code dormant.
     The switch is one env var (`X402_FACILITATOR_URL`,
     `X402_NETWORK=eip155:8453`,
     `X402_ASSET=0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913`,
     plus `X402_CDP_KEY_ID` + `X402_CDP_PRIVATE_KEY`) and a container
     restart. Time-to-mainnet from a verified-customer trigger is ~2 min.
   - **Trigger to flip mainnet**: a real customer signals they want to
     pay in real USDC, OR the operator runs a verified live test (Bybit
     KYC + $5 USDC on Base mainnet to wallet
     `0x254A42D7c617B38c7B43186e892d3af4bf9D6c44`, then SDK live test
     with `network="base"`).
   - **Risk acknowledged**: an untested mainnet flip means the first
     real customer could hit a CDP JWT format quirk we never
     exercised. Mitigations if needed at flip time: (a) run a live
     test ourselves first, (b) add Slack/email alerts on facilitator
     errors, (c) keep the rollback script ready (one rsync of `.env`
     plus restart).

2. **Directory registrations** — x402scan, Agent.market, MCP registries
   (glama.ai, smithery.ai, mcp.so). Each takes 5–15 min; need name,
   description, GitHub link, demo video or JSON schema.

3. **HN Show HN post** — draft in a new `docs/launch/HN_DRAFT.md`.
   Ideal timing: Tuesday/Wednesday 13:00–15:00 UTC. The pitch is
   "x402 testnet live, paste a 14-digit DART number and pay 0.005
   testnet USDC for an English summary" — testnet keeps the friction
   to zero for the HN crowd, who can grab faucet USDC and try it in
   under a minute.

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
