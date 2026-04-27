# HN "Show HN" Draft

Pre-staged copy-paste content for the Hacker News launch.

## Submission

**URL:** https://news.ycombinator.com/submit

### Fields

**Title** (80-char limit, leave room for "Show HN: " prefix):

```
Show HN: AI summaries of Korean corporate filings, paid in USDC via x402
```

**URL field:**

```
https://koreafilings.com
```

**Text field** (HN does NOT render markdown — keep formatting plain):

```
I built a paywalled API that turns Korean DART (전자공시) corporate disclosures into structured English summaries, paid per call in USDC via the x402 protocol (https://www.x402.org/) on Base.

Try it in ~60 seconds (free testnet USDC from https://faucet.circle.com/):

  pip install koreafilings

  from koreafilings import Client
  with Client(private_key="0x...", network="base-sepolia") as c:
      s = c.get_summary("20260424900874")
      print(s.summary_en)
      print("paid:", c.last_settlement.tx_hash)

The SDK signs an EIP-3009 TransferWithAuthorization, sends it as the X-PAYMENT header, the server verifies with the x402 facilitator, settles 0.005 USDC on-chain, and returns the JSON summary plus the tx hash.

Stack: Java 21 / Spring Boot 3.4 / Postgres 16 / Redis 7 on VPS provider behind Cloudflare Tunnel. Gemini 2.5 Flash-Lite for the summarization. Resilience4j for circuit-breaking the LLM and facilitator.

What's there:

- Live API at https://api.koreafilings.com, polling DART every 30s
- Summary cached forever per rcpt_no — first agent pays the LLM cost, every later one hits a cheap DB lookup at the same price (margin compounds)
- x402 v2 transport (PAYMENT-REQUIRED header + bazaar extension for agent invocability)
- MCP server (koreafilings-mcp) so Claude Desktop / Cursor / Continue can call it as a tool
- OpenAPI 3 spec at /v3/api-docs, discovery at /.well-known/x402
- Indexed at https://www.x402scan.com/server/46ef920d-18db-4255-8ec1-f7233451bec7

Why: raw DART data is free but in Korean and structured for filing clerks, not LLMs. Korean equities carry real information asymmetry vs US/EU — but the entry tax for English-speaking quant teams is "hire someone to read Korean PDFs all day." Per-call x402 felt like the cleanest fit: an autonomous agent watching Korean markets pays $0.005 per filing it cares about, no signup, no API key, no monthly minimum.

Honest caveats: on Base Sepolia testnet for now (mainnet code wired and unit-tested but no live mainnet settlement yet — flipping the env var when the first real customer asks). One paid endpoint live; five more in the roadmap. Repo (MIT): https://github.com/OldTemple91/korea-filings-api

Curious to hear what breaks.
```

---

## First comment (post 5–10 min after submission)

Post this as a reply to your own submission. It deepens the discussion and your comment will appear pinned at the top, helping retain engagement after the initial scroll.

```
OP here. A few notes on the parts I think are most interesting:

The cache is the moat: every summary is generated once and stored as an immutable Postgres row. The first call for a given rcpt_no pays the LLM tokens; every subsequent call is a near-free DB lookup. The price stays flat at 0.005 USDC, so the LLM cost is amortized across however many agents care about that filing. Margins compound as adoption grows. This is also why I felt confident charging per call instead of per token or per month — the marginal cost on the cache-hit path approaches zero.

Why a Java backend for a crypto-payments service: I'm a Spring Boot engineer by trade and wanted to prove the x402 flow is implementable in any stack. The SDK, MCP server, and reference client are Python; the production server is Java. Both ends use JdkClientHttpConnector (not Reactor Netty — its TLS implementation rejects the Korean gov endpoint we poll for source data, learned that the hard way).

x402 v2 quirks I hit: the spec moved the PaymentRequired payload from the body into a base64-encoded PAYMENT-REQUIRED header in v2, but most existing clients (including the popular x402.org facilitator) still read the body. I dual-emit both. The "bazaar" extension (specs/extensions/bazaar.md) isn't optional in x402scan's strict-mode validator — my first two registration attempts were rejected before I added it.

On mainnet: the code path is in the repo (CdpJwtSigner — Ed25519 JWT signed per request and attached as Authorization: Bearer to the CDP facilitator) and unit-tested. But I haven't actually run a live mainnet settlement yet. The friction was getting Base mainnet USDC into a wallet from Korea — Coinbase Korea doesn't surface USDC for small card purchases, and I didn't want to KYC at a secondary exchange just for a $5 smoke test. The launch is on testnet (free, anyone with the Circle faucet can try) and the env-var flip waits for the first real customer asking for mainnet.

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

**Q: Will mainnet pricing be the same?**

Yes — 0.005 USDC per summary stays flat across testnet and mainnet. The LLM cost is the same regardless of which chain settles the payment. Eventually I'll add tiered or volume pricing if that helps high-frequency agents, but flat per-call is the v1.

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
