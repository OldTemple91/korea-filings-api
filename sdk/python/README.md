# koreafilings

Python SDK for [koreafilings.com](https://koreafilings.com) — AI-summarized
Korean DART (금융감독원 전자공시) corporate disclosures, paid per call in USDC
on Base via the [x402](https://www.x402.org/) payment protocol.

**No API keys. No monthly fee. No signup.** The wallet that signs the
payment *is* the identity. First call for a disclosure triggers an LLM
run; every call after costs the same flat fee from the server-side cache.

## Install

```bash
pip install koreafilings
```

## Quickstart

```python
from koreafilings import Client

with Client(private_key="0x...", network="base-sepolia") as client:
    summary = client.get_summary("20260424900874")

    print(f"[{summary.importance_score}/10] {summary.event_type}")
    print(summary.summary_en)
    print("tickers:", summary.ticker_tags)
    print("paid:", client.last_settlement.tx_hash)
```

Example output:

```
[7/10] SINGLE_STOCK_TRADING_SUSPENSION
Global SM's common stock was suspended from trading by the Korea Exchange
following irregular trading patterns observed on April 23, 2026...
tickers: ['001680.KS']
paid: 0x1dbf9885194261e1ad64be8205897ef9c5335ad254921b337c343c23d34b0a72
```

## What you get

Every response is a structured `Summary` with:

| field              | type                  | description                                       |
|--------------------|-----------------------|---------------------------------------------------|
| `rcpt_no`          | `str`                 | DART receipt number                               |
| `summary_en`       | `str`                 | Paraphrased English summary (never verbatim)      |
| `importance_score` | `int` (1–10)          | 10 = M&A / insolvency, 1 = routine admin          |
| `event_type`       | `str`                 | Canonical event taxonomy                          |
| `sector_tags`      | `list[str]`           | GICS-style sector labels                          |
| `ticker_tags`      | `list[str]`           | Affected KRX tickers (`.KS` / `.KQ`)              |
| `actionable_for`   | `list[str]`           | Audience hints (`QUANT`, `M&A`, …)                |
| `generated_at`     | `datetime`            | When the summary was produced                     |

## Pricing

`0.005` USDC per summary call, settled on Base. Call `client.get_pricing()`
for the live machine-readable pricing descriptor including the current
recipient wallet and USDC contract address.

## Getting a wallet and USDC

For testing on Base Sepolia (no real money):

1. Create a test wallet (MetaMask → new account → export private key).
2. Get free Base Sepolia ETH from a faucet.
3. Get free test USDC from the [Circle faucet](https://faucet.circle.com/).
4. Pass the 0x-prefixed private key to `Client(private_key=...)`.

For production use on Base mainnet, fund a wallet with real USDC and
pass `network="base"` instead.

## How payment works

Under the hood, each paid call:

1. GET the endpoint without payment → server returns **402** with a
   [x402 `accepts`](https://www.x402.org/) block describing exact
   amount, USDC contract, network, recipient, and expiry.
2. SDK signs an
   [EIP-3009 `TransferWithAuthorization`](https://eips.ethereum.org/EIPS/eip-3009)
   message locally (your private key never leaves the process).
3. SDK base64-encodes the signed payload into `X-PAYMENT` and retries
   the GET.
4. Server verifies the signature via the x402 facilitator, submits it
   on-chain, streams the JSON body back to you, and attaches the
   settlement proof as `X-PAYMENT-RESPONSE`.

The SDK exposes the proof as `client.last_settlement` so you can log
transaction hashes for your accounting system.

## Error handling

```python
from koreafilings import Client, ApiError, PaymentError, ConfigurationError

try:
    summary = client.get_summary("20260424900874")
except PaymentError as e:
    # Facilitator rejected the signed payment (bad sig, low balance,
    # expired auth, network mismatch).
    print("payment failed:", e.reason, e.detail)
except ApiError as e:
    # Non-payment HTTP failure: 404 unknown rcpt_no, 429 rate limit,
    # 5xx upstream outage.
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
