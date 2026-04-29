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

The agent flow is one free call + one paid call:

  pip install koreafilings

  from koreafilings import Client
  with Client(private_key="0x...", network="base") as c:
      # Free: name → ticker resolution, 3961 KRX-listed companies
      matches = c.find_company("Samsung Electronics")
      ticker = matches[0].ticker  # "005930"

      # Paid: 0.005 × limit USDC, declared dynamically in the 402
      filings = c.get_recent_filings(ticker, limit=5)
      for f in filings:
          print(f"[{f.importance_score}/10] {f.event_type}: {f.summary_en}")
      print("paid:", c.last_settlement.tx_hash)

First mainnet settlement: https://basescan.org/tx/0x681c995e149d3ce5765ea8a3b0f921a45352fccefbd9fc9258bf4f6141eafd7c

Stack: Java 21 / Spring Boot 3.4 / Postgres 16 with pg_trgm fuzzy search / Redis 7 on a Linux VPS behind Cloudflare Tunnel. Gemini 2.5 Flash-Lite for the summarisation, prompted with a 50-row Korean → English filing-type taxonomy + importance anchors so OTHER stays under 5%. Resilience4j circuit-breaks the LLM, the DART poller, and the facilitator independently.

What's there:

- Live at https://api.koreafilings.com, DART polled every 30s
- Free company directory ("Samsung" → 005930) with trigram fuzzy search across Korean and English names — the entry point that earlier rcpt_no-only versions of this API didn't give agents
- Free recent-filings feed (metadata only) so an agent can browse before paying
- Paid single-summary at 0.005 USDC and paid by-ticker batches at 0.005 × limit USDC, dynamic price declared in the 402 response
- x402 v2 transport (PAYMENT-REQUIRED header) + bazaar extension declaring input/output schema so agents can autonomously invoke
- MCP server (koreafilings-mcp 0.2.1 on PyPI) — five tools (find_company, list_recent_filings, get_pricing, get_recent_filings, get_disclosure_summary) usable from Claude Desktop / Cursor / Continue
- OpenAPI at /v3/api-docs, discovery at /.well-known/x402
- Indexed at https://www.x402scan.com/server/46ef920d-18db-4255-8ec1-f7233451bec7

Why: raw DART data is free but in Korean and structured for filing clerks, not LLMs. Korean equities carry real information asymmetry vs US/EU — but the entry tax for English-speaking quant teams is "hire someone to read Korean PDFs all day." Per-call x402 means an autonomous agent watching Korea pays cents per signal, with no signup, no API key, no monthly minimum.

Honest scope:

- Today's summaries are generated from filing metadata only — title, date, filer, DART flag. That gives event type / importance score / sector / ticker reliably (first-pass screening) but the LLM honestly says "details are in the filing body" for quantitative events instead of fabricating numbers. Whether to extract the actual amounts is the v1.2 question.
- v1.2 plan, on the roadmap: pull the per-filing XBRL via DART's /document.xml and template-extract numbers (issuance amount, dilution %, contract value, …) for the six highest-value event types into a structured keyFacts field. New paid endpoint /v1/disclosures/{rcptNo}/deep at ~0.020 USDC; existing endpoints stay metadata-only at 0.005 USDC so callers pick depth at call time.

Repo (MIT, Java backend + Python SDK + Python MCP + landing): https://github.com/OldTemple91/korea-filings-api

Would love feedback on what filing types you'd most want quantitative depth for, what's missing from the agent flow, and whether the per-result pricing in the 402 challenge feels intuitive.
```

---

## First comment (post 5–10 min after submission)

Post this as a reply to your own submission. It deepens the discussion and your comment will appear pinned at the top, helping retain engagement after the initial scroll.

```
OP here. A few notes on the parts I think are most interesting:

The cache is the moat: every summary is generated once and stored as an immutable Postgres row. The first call for a given rcpt_no pays the LLM tokens; every subsequent call is a near-free DB lookup. The price stays flat at 0.005 USDC, so the LLM cost is amortized across however many agents care about that filing. Margins compound as adoption grows. This is also why I felt confident charging per call instead of per token or per month — the marginal cost on the cache-hit path approaches zero.

Why a Java backend for a crypto-payments service: most x402 examples in the wild are TypeScript, and I wanted a worked reference proving the flow lands clean on the JVM too. The SDK, MCP server, and reference client are Python; the production server is Java. Both ends use JdkClientHttpConnector (not Reactor Netty — its TLS implementation rejects the Korean gov endpoint we poll for source data, learned that the hard way).

x402 v2 quirks I hit: the spec moved the PaymentRequired payload from the body into a base64-encoded PAYMENT-REQUIRED header in v2, but most existing clients (including the popular x402.org facilitator) still read the body. I dual-emit both. The "bazaar" extension (specs/extensions/bazaar.md) isn't optional in x402scan's strict-mode validator — my first two registration attempts were rejected before I added it.

The bug I caught right before launch: testnet USDC's EIP-712 domain `name` is "USDC", but the Base mainnet contract's `name()` returns "USD Coin" (full ERC-20 token name). The first mainnet attempt failed with the CDP facilitator's `transferWithAuthorization` simulation reverting and a tight `invalid_payload` error. The fix was a one-line config swap (X402_TOKEN_NAME=USD Coin) — both networks now coexist via env-var-driven domain values. If you're building anything that signs against multiple USDC deployments, this footgun is worth knowing about.

Happy to dig into any of this.
```

---

## Likely-question talk track

Pre-thought answers for the highest-probability comments. Keep responses short and direct — HN rewards concision.

**Q: Why should agents care about Korean disclosures?**

Information asymmetry. Korean equities trade on KRX, but English-language coverage lags 30 minutes to several hours after a filing. Quant strategies that watch Korea in real time have a meaningful information edge over those that wait for Bloomberg / Reuters to translate. An autonomous agent paying 0.005 USDC per filing it cares about can monitor the entire market for less than a coffee per day.

**Q: Why x402 instead of API keys?**

Trust model is reversed. With an API key, the merchant trusts the customer to pay. With x402, payment IS the auth — the wallet that signed the EIP-3009 authorization is the identity. No signup, no KYC, no chargebacks, no monthly minimum. Especially nice for autonomous agents that aren't great at filling out signup forms.

**Q: How do you handle copyright on Korean filings?**

Summaries are paraphrased English, never verbatim translations of the source PDF. Each response also returns a link to the original DART filing (free, public domain by Korean Financial Services Commission) so the end user can verify against the source.

**Q: Why not open-source the cache?**

The code is open (MIT). The cache contents (actual summaries) are the value the service sells — opening that defeats the per-call pricing. This is the same delta as PostgreSQL (open-source) vs. a hosted Postgres provider's customer data.

**Q: What's the threat model on the wallet private key?**

The SDK signs locally; the key never leaves the caller's process. The server never sees it. The recommended pattern (and what the README says) is a fresh burner wallet funded only with the USDC you intend to spend — same threat model as any merchant integration.

**Q: What if Gemini 429s?**

Resilience4j RateLimiter (10 RPM, conservative below the 15 RPM free-tier ceiling) gates outbound calls; CircuitBreaker on the gemini provider trips after sustained failures; the SummaryRetry scheduler re-enqueues failed jobs on a separate cadence. None of this affects paying readers because cache hits short-circuit the whole pipeline; only the first caller for a given rcpt_no can possibly hit a 429-driven slow path.

**Q: Pricing roadmap?**

0.005 USDC per metadata summary stays flat — marginal cost on a cache hit is near-zero, so per-call flat pricing made more sense than tokens or subscriptions. The next pricing tier is v1.2 deep analysis at ~0.020 USDC (new /v1/disclosures/{rcptNo}/deep endpoint pulling the filing body for quantitative facts), which lets agents pick depth at call time. Volume tiers would land only if data shows high-frequency agents repeatedly hitting the same handful of tickers — but the cache moat already covers that case for free.

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
