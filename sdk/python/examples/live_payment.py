#!/usr/bin/env python3
"""Live end-to-end payment against https://api.koreafilings.com.

Mirrors the TypeScript SDK's `examples/live-payment.ts`: resolves a
Korean company name to a ticker (free), then fetches a batch of AI
summaries for that ticker (paid via x402). Costs `0.005 × KF_LIMIT`
USDC per run; default `KF_LIMIT=2` settles for 0.01 USDC.

Reuses the same testclient/.env.testclient the repo already ships so
you don't need to export the private key again. Only prints public
surface — payer address, summary, and settlement tx hash.

Run from repo root:
    source sdk/python/.venv/bin/activate
    python sdk/python/examples/live_payment.py

Override defaults:
    KF_NETWORK=base KF_QUERY="삼성전자" KF_LIMIT=3 \
        python sdk/python/examples/live_payment.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from dotenv import load_dotenv

from koreafilings import ApiError, Client, PaymentError


def main() -> None:
    env_path = Path(__file__).resolve().parents[3] / "testclient" / ".env.testclient"
    if not env_path.exists():
        sys.exit(f"Missing {env_path}")
    load_dotenv(env_path)

    pk = os.environ.get("PAYER_PRIVATE_KEY", "").strip()
    if not pk:
        sys.exit("Set PAYER_PRIVATE_KEY in testclient/.env.testclient.")

    network = os.environ.get("KF_NETWORK", "base")
    query = os.environ.get("KF_QUERY", "Samsung Electronics")
    limit = int(os.environ.get("KF_LIMIT", "2"))
    base_url = os.environ.get("API_BASE_URL", "https://api.koreafilings.com").rstrip("/")

    with Client(private_key=pk, network=network, base_url=base_url) as client:
        print(f"payer wallet: {client.address}")

        try:
            # 1. Free — Korean / English name to KRX ticker.
            matches = client.find_company(query)
        except ApiError as e:
            sys.exit(f"API ERROR {e.status_code}: {e.body}")

        if not matches:
            sys.exit(f"no matches for query={query!r}")

        ticker = matches[0].ticker
        name = matches[0].name_en or matches[0].name_kr
        print(f"resolved {name} → {ticker}")

        # 2. Paid — 0.005 × limit USDC, settled in one round-trip.
        try:
            filings = client.get_recent_filings(ticker, limit=limit)
        except PaymentError as e:
            sys.exit(f"PAYMENT ERROR: {e}")
        except ApiError as e:
            sys.exit(f"API ERROR {e.status_code}: {e.body}")

        for f in filings:
            print(f"[{f.importance_score}/10] {f.event_type}: {f.summary_en}")

        if client.last_settlement and client.last_settlement.tx_hash:
            tx = client.last_settlement.tx_hash
            cost = 0.005 * limit
            print(f"paid {cost:.3f} USDC")
            scan = "https://basescan.org/tx" if network == "base" or network == "base-mainnet" \
                else "https://sepolia.basescan.org/tx"
            print(f"tx: {tx}")
            print(f"{scan}/{tx}")
        else:
            print("(no settlement proof — server may have served from free tier)")


if __name__ == "__main__":
    main()
