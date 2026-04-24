# x402 Test Client

Standalone Python script that exercises the paid `GET /v1/disclosures/{rcptNo}/summary` endpoint end-to-end: fetches the 402 payment requirements, signs an EIP-3009 `TransferWithAuthorization` with the payer's private key, retries with the base64 `X-PAYMENT` header, and decodes the `X-PAYMENT-RESPONSE` settlement proof.

## One-time setup

Pick one:

### Option A — `uv` (recommended, faster)

```bash
cd testclient
cp .env.testclient.example .env.testclient
# Edit .env.testclient: paste account 2 private key, set target rcpt_no
uv run --with-requirements requirements.txt payer.py
```

### Option B — `venv` + `pip`

```bash
cd testclient
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
cp .env.testclient.example .env.testclient
# Edit .env.testclient
python payer.py
```

## Running against the local server

```bash
# In one terminal, with the Spring app and docker compose up:
set -a && source .env && set +a && ./gradlew bootRun \
  --args='--dart.polling.enabled=false --summary.consumer.enabled=false \
          --summary.retry.enabled=false --summary.backfill.enabled=false'

# In another terminal:
uv run testclient/payer.py 20260423000001
```

Expected output:

```
Payer address : 0x...
Target        : http://localhost:8080/v1/disclosures/20260423000001/summary

[402] Payment required:
{ "x402Version": 2, "accepts": [ { ... } ] }

[SIGNED] nonce=0xabcd…  value=5000  sig_len=132

[RETRY] status=200
[SETTLED]
{ "success": true, "transaction": "0x...", "network": "eip155:84532", "payer": "0x..." }

[SUMMARY]
{ "rcptNo": "20260423000001", "summaryEn": "Samsung ...", "importanceScore": 9, ... }
```

## Arguments

`payer.py` takes one optional positional argument — the `rcpt_no` to request. If omitted, `TARGET_RCPT_NO` from `.env.testclient` is used.

```bash
uv run testclient/payer.py                      # uses TARGET_RCPT_NO
uv run testclient/payer.py 20260423000001       # overrides
```

## Security

- `PAYER_PRIVATE_KEY` must be a **fresh test-only wallet** — never reuse a personal wallet that holds real assets.
- `.env.testclient` is gitignored. Verify before sharing anything: `git check-ignore testclient/.env.testclient`.
- Fund the payer wallet with **Base Sepolia USDC only** (from https://faucet.circle.com/). Never mainnet USDC in this setup.
- The facilitator pays gas, so the payer wallet does not need Base Sepolia ETH for x402 payments. A tiny amount (0.01 ETH from https://www.alchemy.com/faucets/base-sepolia) is useful for unrelated debugging.

## What the script does under the hood

1. `GET /v1/disclosures/{rcpt_no}/summary` without any header — expects `402 Payment Required`.
2. Parses `body.accepts[0]` for scheme (`exact`), network (`eip155:84532` for Base Sepolia), USDC asset address, recipient, and atomic amount.
3. Builds an EIP-3009 `TransferWithAuthorization` struct with a random 32-byte nonce and a short `validBefore` window.
4. Signs it via EIP-712 with `PAYER_PRIVATE_KEY` — domain uses the USDC contract name/version from `accepts[0].extra`.
5. Wraps the signed authorization in a `PaymentPayload` JSON object and base64-encodes it into the `X-PAYMENT` header.
6. Re-issues the `GET` with the header — server verifies with the facilitator, returns the summary body, settles on-chain, and attaches the proof to `X-PAYMENT-RESPONSE`.
7. Decodes and prints the settlement info (including the on-chain transaction hash you can look up on https://sepolia.basescan.org).
