# Roadmap — DART Intelligence API

Six-week plan from zero to public launch. Weekends-only work assumed (~10 hours/week). If weekday time appears, use it to catch up or get ahead, not to sprint into later weeks.

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
- Annotate `GET /v1/disclosures/{rcptNo}/summary` with `@X402Paywall(priceUsdc = "0.005", network = "base-sepolia")`.
- Implement the controller.
- Write a test client in Python or TypeScript that:
   1. Calls the endpoint without payment → receives 402 with requirements.
   2. Signs a USDC transfer with ethers.js.
   3. Retries with `X-PAYMENT` header → receives 200 with summary.
- Debug until the flow works end-to-end.

Deliverable: a demo video-able payment flow on localhost against testnet USDC.

## Week 4 — Public Deployment

Goal: the API is reachable at `api.{domain}/v1/...` from the public internet, with HTTPS, not exposing the home IP.

**Day 1**
- Buy a domain (owner's choice; `korwire.io` / `kordata.io` / etc.).
- Point the domain's DNS to Cloudflare (transfer nameservers).
- Install `cloudflared` on the home server.
- Create a Cloudflare Tunnel in the dashboard, connect to the server, route `api.{domain}` → `http://app:8080` (inside Docker Compose).
- Verify external reachability with `curl https://api.{domain}/v1/pricing`.

**Day 2**
- Switch from Base Sepolia to Base mainnet in production config.
- Purchase $5 worth of real USDC on Base to test live flow.
- Run the test client against production once. Confirm settlement appears in the payer wallet and funds arrive in the owner's wallet.
- Set up Prometheus + Grafana in Docker Compose. Import a basic Spring Boot dashboard.
- Install `monit` or equivalent for process health.
- Write the status page (can be a static HTML on Cloudflare Pages).

Deliverable: a working paid API at a real URL, observable in Grafana.

## Week 5 — Distribution

Goal: somebody other than the owner can find this API and use it.

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

Deliverable: a person who has never heard of the owner can find the service via x402scan, MCP registry, or Google.

## Week 6 — Launch

Goal: first external paid call.

**Day 1**
- Post "Show HN: DART Intelligence API — Korean corporate disclosures for AI agents, paid per call via x402" on Hacker News on a Tuesday or Wednesday morning US time.
- Cross-post on r/LocalLLaMA, r/MachineLearning, r/ClaudeAI with angle appropriate to each.
- Post on GeekNews (Korean), OKKY, and owner's personal velog/Twitter.
- Monitor Grafana for traffic and errors. Stay available for the first 6 hours to answer questions.

**Day 2**
- Review metrics. Document bugs found during launch.
- Post a followup post on the owner's blog — "What I learned building my first x402 API" — covering technical choices, cost structure, launch numbers (however embarrassing).
- Start a changelog at `{domain}/changelog`.
- Stop coding. Watch the data for a week before deciding what to build next.

Deliverable: at least one paid call from an external wallet. Plus, honest metrics written down for future iteration.

## Post-Launch (Weeks 7+)

Do not commit to features ahead of time. Look at what the data says. Some likely directions depending on what the telemetry shows:

- If traffic is dominated by `/latest`: add SSE streaming endpoint.
- If traffic concentrates on specific tickers: add a cheap "watchlist" endpoint that bundles multiple filings per call.
- If customers ask for earnings data: build earnings call summary as a separate product line under the same domain.
- If volume stays low: diagnose (pricing? discoverability? wrong customer?) before building anything new.

## Guardrails Throughout

- No side-project resources (*** code, data, or accounts) used at any point.
- Every external dependency (DART, Gemini, OpenAI, Coinbase) gated behind a client class with timeouts and a circuit breaker.
- Secrets only in `.env`, never in the repo. `.env.example` documents all variables.
- Before every week's work: re-read `CLAUDE.md` and `PRD.md`. Update them if reality has diverged.
- Spend no money that cannot be replaced from the month's normal budget. If the project is not at $50/month in revenue by month 4, scale costs down, not up.
