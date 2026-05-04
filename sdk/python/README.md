# koreafilings

Python SDK for [koreafilings.com](https://koreafilings.com) — AI-summarized
Korean DART (금융감독원 전자공시) corporate disclosures, paid per call in USDC
on Base via the [x402](https://www.x402.org/) payment protocol.

**No API keys. No monthly fee. No signup.** The wallet that signs the
payment *is* the identity. Free company-directory and recent-filings
feeds let an agent browse before paying; paid endpoints settle on
Base mainnet via Coinbase's CDP facilitator. The first call for a
disclosure pays the LLM cost; every subsequent call hits the
server-side cache for the same flat fee.

## Install

```bash
pip install koreafilings
```

## Quickstart — name to summary in two calls

The natural agent flow is **one free call to resolve a company by
name, one paid call to fetch summaries by ticker**:

```python
from koreafilings import Client

with Client(private_key="0x...", network="base") as client:
    # 1. Free name → ticker resolution. Matches Korean and English
    #    names with trigram fuzzy search across 3,961 KRX-listed
    #    companies.
    matches = client.find_company("Samsung Electronics")
    ticker = matches[0].ticker  # "005930"

    # 2. Paid batch summary fetch. Price is declared dynamically in
    #    the 402 challenge as 0.005 × limit USDC, so the SDK signs
    #    the exact amount before settling.
    filings = client.get_recent_filings(ticker, limit=5)
    for f in filings:
        print(f"[{f.importance_score}/10] {f.event_type}: {f.summary_en}")

    print("paid:", client.last_settlement.tx_hash)
```

Example output:

```
[7/10] SUPPLY_CONTRACT_SIGNED: Samsung Electronics announced...
[5/10] DIVIDEND_DECISION: Samsung Electronics' Q1 2026 dividend...
[3/10] OTHER: Quarterly business report for Q1 2026...
[8/10] MAJOR_SHAREHOLDER_FILING: National Pension Service raised...
[4/10] OTHER: Disclosure of an executive's stock ownership change...
paid: 0x37397cee5f9c8a1d2...
```

If you already know the receipt number (e.g. you stored one
yesterday and want to re-fetch on the same flat 0.005 USDC tier):

```python
summary = client.get_summary("20260424900874")
```

## Free discovery endpoints

Three endpoints carry no payment challenge so an agent can plan
before spending USDC:

```python
# 1. Search by name or ticker (Korean and English, trigram fuzzy):
matches = client.find_company("삼성전자")          # Korean
matches = client.find_company("Samsung Electronics")  # English
matches = client.find_company("005930")            # ticker
for m in matches:
    print(m.ticker, m.name_kr, m.name_en, m.market)

# 2. Direct ticker lookup:
co = client.get_company("005930")

# 3. Browse the market-wide recent feed (metadata only — no LLM
#    summary, but lets the agent see what's hot before paying):
recent = client.list_recent_filings(limit=20)
for r in recent:
    print(r.rcpt_no, r.ticker, r.corp_name, r.report_nm, r.rcept_dt)
```

## Pricing

| Endpoint | Method | Price (USDC) |
|---|---|---|
| `client.find_company(q)` | `GET /v1/companies` | free |
| `client.get_company(ticker)` | `GET /v1/companies/{ticker}` | free |
| `client.list_recent_filings(limit)` | `GET /v1/disclosures/recent` | free |
| `client.get_recent_filings(ticker, limit)` | `GET /v1/disclosures/by-ticker?ticker=…&limit=…` | **0.005 × limit** |
| `client.get_summary(rcpt_no)` | `GET /v1/disclosures/summary?rcptNo=…` | **0.005** |

Per-result pricing on the by-ticker endpoint is declared dynamically
in the 402 response: the SDK reads `accepts[0].amount` from the
challenge and signs the exact charge for the requested `limit`.
Direct receipt-number lookup stays at a flat 0.005 USDC.

`client.get_pricing()` returns the live machine-readable pricing
descriptor including the current recipient wallet, network, and
USDC contract address.

## What you get

`get_recent_filings` and `get_summary` return structured `Summary`
objects (or a `list[Summary]` for the batch endpoint). Each carries:

| field              | type                  | description                                       |
|--------------------|-----------------------|---------------------------------------------------|
| `rcpt_no`          | `str`                 | DART receipt number                               |
| `summary_en`       | `str`                 | Paraphrased English summary (never verbatim)      |
| `importance_score` | `int` (1–10)          | 10 = M&A / insolvency, 1 = routine admin          |
| `event_type`       | `str`                 | Canonical event taxonomy                          |
| `sector_tags`      | `list[str]`           | GICS-style sector labels                          |
| `ticker_tags`      | `list[str]`           | Affected KRX tickers (`.KS` / `.KQ`)              |
| `actionable_for`   | `list[str]`           | Audience hints (`traders`, `long_term_investors`, …) |
| `generated_at`     | `datetime`            | When the summary was produced                     |

`find_company` / `get_company` return `Company` records (`ticker`,
`corp_code`, `name_kr`, `name_en`, `market`). `list_recent_filings`
returns `RecentFiling` records (`rcpt_no`, `ticker`, `corp_name`,
`report_nm`, `rcept_dt`) — metadata only, no summary.

> **Honest scope.** Today's summaries are generated from filing
> *metadata* (title, date, filer, DART flag) only. That gives event
> type / importance / sector / ticker reliably — first-pass
> screening — but the LLM honestly says "details are in the filing
> body" for quantitative events instead of fabricating numbers.
> v1.2 (planned) introduces a `/v1/disclosures/{rcptNo}/deep`
> endpoint at ~0.020 USDC that pulls the per-filing XBRL via DART's
> `/document.xml` and template-extracts amounts, dilution %,
> counterparty, and dates into a structured `keyFacts` field. See
> the [roadmap](https://github.com/OldTemple91/korea-filings-api/blob/main/docs/ROADMAP.md#v12--deep-filing-analysis-planned).

## Getting a wallet and USDC

Production use on Base mainnet:

1. Create a fresh burner wallet (MetaMask → new account → export
   private key). **Do not reuse an existing personal wallet** — the
   private key signs real-money authorizations.
2. Fund it with the USDC you intend to spend (a few dollars covers
   hundreds of summaries).
3. Pass the 0x-prefixed key to `Client(private_key=..., network="base")`.

For local development against the public testnet facilitator, point
the SDK at Base Sepolia:

```python
client = Client(private_key="0x...", network="base-sepolia")
```

Get free Base Sepolia ETH from the
[Coinbase faucet](https://www.coinbase.com/faucets/base-ethereum-sepolia-faucet)
and free test USDC from the [Circle faucet](https://faucet.circle.com/).

## How payment works

Under the hood, each paid call:

1. **GET** the endpoint without payment → server returns **402** with
   an [x402 v2 `accepts`](https://www.x402.org/) block describing
   exact amount, USDC contract, network, recipient, and expiry.
2. SDK signs an
   [EIP-3009 `TransferWithAuthorization`](https://eips.ethereum.org/EIPS/eip-3009)
   message locally — your private key never leaves the process.
3. SDK base64-encodes the signed payload into `X-PAYMENT` and retries
   the GET.
4. Server verifies the signature via the x402 facilitator, submits it
   on-chain, streams the JSON body back, and attaches the settlement
   proof as `X-PAYMENT-RESPONSE`.

The SDK exposes the proof as `client.last_settlement` so you can log
transaction hashes for your accounting system.

## Error handling

```python
from koreafilings import Client, ApiError, PaymentError, ConfigurationError

try:
    matches = client.find_company("Samsung Electronics")
    filings = client.get_recent_filings(matches[0].ticker, limit=5)
except PaymentError as e:
    # Facilitator rejected the signed payment (bad sig, low balance,
    # expired auth, network mismatch).
    print("payment failed:", e.reason, e.detail)
except ApiError as e:
    # Non-payment HTTP failure: 404 unknown ticker / rcpt_no, 429
    # rate limit, 5xx upstream outage.
    print("api error:", e.status_code, e.body)
except ConfigurationError as e:
    # Bad private_key format or unknown network alias.
    print("config:", e)
```

## Security notes

- **The private key signs real-money authorizations.** Do not ship it
  in client-side code, do not put it in a `.env` checked into git, do
  not paste it in chat. Prefer a dedicated burner wallet funded with
  only the USDC you plan to spend.
- The SDK signs locally; the key is never transmitted to koreafilings.com
  or to the facilitator.
- Every payment carries an `EIP-3009` nonce. The facilitator refuses
  replays.
- For Base Sepolia testing only, a fresh wallet with faucet funds is
  the safest pattern — nothing on that wallet has production value.

## Source & feedback

- Repo: <https://github.com/OldTemple91/korea-filings-api>
- Issues: <https://github.com/OldTemple91/korea-filings-api/issues>
- Landing: <https://koreafilings.com>

MIT-licensed.
