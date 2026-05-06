# HN "Show HN" Draft

Pre-staged copy-paste content for the Hacker News launch.

## Submission

**URL:** https://news.ycombinator.com/submit

### Fields

**Title** (80-char limit, leave room for "Show HN: " prefix):

```
Show HN: Search Korean corporate filings by name, pay per call in USDC via x402
```

(Alternative: `Show HN: 1 free call + 1 paid call to get Korean DART filings as English signals`)

**URL field:**

```
https://koreafilings.com
```

**Text field** (HN does NOT render markdown — keep formatting plain):

```
I built a paywalled API that turns Korean DART (전자공시) corporate disclosures into structured English signals, paid per call in USDC via the x402 protocol (https://www.x402.org/) on Base mainnet.

The agent flow is one free call + one paid call. TypeScript:

  npm install koreafilings

  import { KoreaFilings } from 'koreafilings'
  const c = new KoreaFilings({ privateKey: '0x...', network: 'base' })
  // Free: name → ticker, 3961 KRX-listed companies, fuzzy
  const m = await c.findCompany('Samsung Electronics')
  // Paid: 0.005 × limit USDC, declared dynamically in the 402
  const f = await c.getRecentFilings(m[0].ticker, 5)
  console.log('paid:', c.lastSettlement?.transaction)

Or Python:

  pip install koreafilings

  from koreafilings import Client
  with Client(private_key="0x...", network="base") as c:
      matches = c.find_company("Samsung Electronics")
      filings = c.get_recent_filings(matches[0].ticker, limit=5)
      print("paid:", c.last_settlement.tx_hash)

Or via MCP (Claude Desktop / Cursor / Continue) — `uv tool install
koreafilings-mcp`, plug a wallet into the env, ask in English.

First mainnet settlement: https://basescan.org/tx/0x681c995e149d3ce5765ea8a3b0f921a45352fccefbd9fc9258bf4f6141eafd7c

Stack: Java 21 / Spring Boot 3.4 / Postgres 16 with pg_trgm fuzzy search / Redis 7 on a Linux VPS behind Cloudflare Tunnel. Gemini 2.5 Flash-Lite for the summarisation, prompted with a 50-row Korean → English filing-type taxonomy + importance anchors so OTHER stays under 5%. Resilience4j circuit-breaks the LLM, the DART poller, and the facilitator independently.

What's there:

- Live at https://api.koreafilings.com, DART polled every 30s
- Free company directory ("Samsung" → 005930) with trigram fuzzy search across Korean and English names — the entry point that earlier rcpt_no-only versions of this API didn't give agents
- Free recent-filings feed (metadata only) so an agent can browse before paying
- Paid single-summary at 0.005 USDC and paid by-ticker batches at 0.005 × limit USDC, dynamic price declared in the 402 response
- x402 v2 transport (PAYMENT-REQUIRED header) + bazaar extension declaring input/output schema so agents can autonomously invoke
- TypeScript SDK (koreafilings 0.1.0 on npm) and Python SDK (koreafilings 0.3.1 on PyPI) — same surface in both languages, ESM + CJS for the TS one
- MCP server (koreafilings-mcp 0.3.0 on PyPI) — five tools (find_company, list_recent_filings, get_pricing, get_recent_filings, get_disclosure_summary) usable from Claude Desktop / Cursor / Continue
- OpenAPI at /v3/api-docs, discovery at /.well-known/x402
- Indexed at https://www.x402scan.com/server/46ef920d-18db-4255-8ec1-f7233451bec7

Why: raw DART data is free but in Korean and structured for filing clerks, not LLMs. Per-call x402 means an autonomous agent watching Korea pays cents per signal — no signup, no API key, no monthly minimum, no procurement loop. The pay-per-call delivery model is the wedge; Korean financial data is the surface we ship today, but the same shape applies to any other public-records API behind the kind of subscription paywall agents can't navigate.

Honest scope:

- Summaries are body-aware — the model reads the filing body itself (fetched lazily via DART's /document.xml ZIP, parsed and capped at 20,000 chars) so quantitative events surface concrete amounts, dilution percentages, counterparty names, and effective dates. Body fetch is lazy: the first paid call per rcpt_no fetches and caches the body in Postgres, every subsequent call hits the cache. If DART hasn't finalised the body yet (very fresh filings) the LLM falls back to title-only and the response notes the missing detail explicitly rather than fabricating numbers.
- A future tiered-pricing iteration (event-type clusters, e.g. LOW/STANDARD/HIGH) sits on the roadmap, but the standing 0.005 USDC flat price already covers body-aware summarisation today — the tier change would be triggered by traffic data, not by a depth gap.

Repo (MIT, Java backend + Python SDK + Python MCP + landing): https://github.com/OldTemple91/korea-filings-api

Would love feedback on what filing types you'd most want quantitative depth for, what's missing from the agent flow, and whether the per-result pricing in the 402 challenge feels intuitive.
```

---

## First comment (post 5–10 min after submission)

Post this as a reply to your own submission. It deepens the discussion and your comment will appear pinned at the top, helping retain engagement after the initial scroll.

```
OP here. A few notes on the parts I think are most interesting:

The cache is the moat: every summary is generated once and stored as an immutable Postgres row. The first call for a given rcpt_no pays the LLM tokens; every subsequent call is a near-free DB lookup. The price stays flat at 0.005 USDC, so the LLM cost is amortized across however many agents care about that filing. Margins compound as adoption grows. This is also why I felt confident charging per call instead of per token or per month — the marginal cost on the cache-hit path approaches zero.

Why a Java backend for a crypto-payments service: most x402 examples in the wild are TypeScript, and I wanted a worked reference proving the flow lands clean on the JVM too. The TypeScript and Python SDKs have the same shape; the MCP server is Python; the production server is Java. The repo and the npm/PyPI packages all use `JdkClientHttpConnector` on the server side and native fetch on the client side (Node 18+ / browsers / Workers / Deno) — no `node-fetch` dependency needed. Reactor Netty was tried and removed; its TLS implementation rejects the Korean gov endpoint we poll for source data, learned that the hard way.

x402 v2 quirks I hit: the spec moved the PaymentRequired payload from the body into a base64-encoded PAYMENT-REQUIRED header in v2, but most existing clients (including the popular x402.org facilitator) still read the body. I dual-emit both. The "bazaar" extension (specs/extensions/bazaar.md) isn't optional in x402scan's strict-mode validator — my first two registration attempts were rejected before I added it.

The bug I caught right before launch: testnet USDC's EIP-712 domain `name` is "USDC", but the Base mainnet contract's `name()` returns "USD Coin" (full ERC-20 token name). The first mainnet attempt failed with the CDP facilitator's `transferWithAuthorization` simulation reverting and a tight `invalid_payload` error. The fix was a one-line config swap (X402_TOKEN_NAME=USD Coin) — both networks now coexist via env-var-driven domain values. If you're building anything that signs against multiple USDC deployments, this footgun is worth knowing about.

Happy to dig into any of this.
```

---

## Likely-question talk track

Pre-thought answers for the highest-probability comments. Keep responses short and direct — HN rewards concision.

**Q: Who actually pays for this?**

x402-capable indie agent builders, mostly. Real-money buyers of Korean financial data (Bloomberg / Refinitiv / FnGuide subscribers) won't be on USDC rails any time soon. The realistic ICP today is autonomous agents that pull diverse data sources and prefer pay-per-call to subscriptions — same audience x402 itself was designed for. Korean data is one of many such sources they pay cents per call for.

**Q: Why should agents care about Korean disclosures specifically?**

English-language coverage of KRX filings lags 30 minutes to several hours behind the source. An autonomous agent paying 0.005 USDC per filing it cares about can monitor the entire market for less than a coffee per day, with structured importance scoring + entity tagging + sector tags wired in.

**Q: Why x402 instead of API keys?**

Trust model is reversed. With an API key, the merchant trusts the customer to pay. With x402, payment IS the auth — the wallet that signed the EIP-3009 authorization is the identity. No signup, no KYC, no chargebacks, no monthly minimum. Especially nice for autonomous agents that aren't great at filling out signup forms.

**Q: How do you handle copyright on Korean filings?**

Summaries are paraphrased English, never verbatim translations of the source PDF. Each response also returns a link to the original DART filing (free, public domain by Korean Financial Services Commission) so the end user can verify against the source.

**Q: Why not open-source the cache?**

The code is open (MIT). The cache contents (actual summaries) are the value the service sells — opening that defeats the per-call pricing. This is the same delta as PostgreSQL (open-source) vs. a hosted Postgres provider's customer data.

**Q: What's the threat model on the wallet private key?**

The SDK signs locally; the key never leaves the caller's process. The server never sees it. The recommended pattern (and what the README says) is a fresh burner wallet funded only with the USDC you intend to spend — same threat model as any merchant integration.

**Q: What if Gemini 429s?**

Resilience4j RateLimiter (10 RPM, conservative below the 15 RPM free-tier ceiling) gates outbound calls; CircuitBreaker on the gemini provider trips after sustained failures. None of this affects paying readers because cache hits short-circuit the whole pipeline; only the first caller for a given rcpt_no can possibly hit a 429-driven slow path. On a 429 the controller returns 503 — settlement-on-2xx leaves the caller uncharged, so a Gemini outage doesn't silently take revenue.

**Q: Pricing roadmap?**

0.005 USDC per summary stays flat — marginal cost on a cache hit is near-zero, so per-call flat pricing made more sense than tokens or subscriptions. Body-aware summarisation (the v1.2-flavour "deep" tier originally planned) ships at the same 0.005 USDC price; pulling the body fetch forward into v1.1 turned out cleaner than maintaining two pricing tiers when the LLM cost difference between metadata-only and body-aware Flash-Lite calls is well under a cent. A future tiered-pricing iteration (event-type clusters) sits on the roadmap and would be triggered by traffic data showing which events drive disproportionate paid volume, not by the depth gap. Volume tiers would land only if high-frequency agents repeatedly hit the same handful of tickers — but the cache moat already covers that case for free.

**Q: Korean text in console?**

UTF-8 throughout. Spring boot has -Dfile.encoding=UTF-8 baked into the Dockerfile entrypoint, the Postgres database is UTF-8, the LLM prompt is UTF-8. The 한글 in the title is for SEO + cultural signal — most of the workflow is in English from the API consumer's perspective.

---

## Timing

**Best:** Tuesday or Wednesday, 13:00–15:00 UTC (22:00–24:00 KST).

Why: that window is morning in US East Coast (largest HN cohort) and afternoon in Europe — peak HN traffic. Avoid Mondays (post often gets buried under weekend backlog) and Fridays (front-page momentum dies into the weekend).

**Avoid:** US holidays, Apple/Google keynotes, major OpenAI/Anthropic releases (post will be drowned).

## Posting protocol

1. Submit during the window above.
2. Within 5 min, post the "first comment" template.
3. For the next 30–60 min, refresh the page every few minutes and reply to comments quickly. Early engagement is what pushes a Show HN from /new to the front page.
4. After 1 hour, the trajectory is mostly set. Keep replying for another 2–4 hours then taper.
5. Don't beg for upvotes. Don't post the link in /r/HackerNews or pay for upvote services — HN catches both and shadowbans.

## What "going well" looks like

- 30+ upvotes in the first hour → strong front page chance
- 5+ substantive comments → engagement signal HN's algorithm rewards
- Made it to front page (top 30) within 2 hours → 10k–100k pageviews over 24h
- Did not make front page → still 1k–5k pageviews from /new + the followers of anyone who upvoted, plus permanent SEO presence on the post URL.
