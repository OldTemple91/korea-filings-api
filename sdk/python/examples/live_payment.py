#!/usr/bin/env python3
"""Live end-to-end payment against https://api.koreafilings.com.

Reuses the same testclient/.env.testclient the repo already ships so
you don't need to export the private key again. Only prints public
surface — payer address, summary, and settlement tx hash.

Run from repo root:
    source sdk/python/.venv/bin/activate
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

    pk = os.environ["PAYER_PRIVATE_KEY"].strip()
    rcpt_no = os.environ.get("TARGET_RCPT_NO", "20260424900874")
    base_url = os.environ.get("API_BASE_URL", "https://api.koreafilings.com").rstrip("/")

    with Client(private_key=pk, network="base-sepolia", base_url=base_url) as client:
        print(f"payer: {client.address}")
        print(f"rcpt : {rcpt_no}")
        print(f"base : {base_url}\n")

        try:
            summary = client.get_summary(rcpt_no)
        except PaymentError as e:
            sys.exit(f"PAYMENT ERROR: {e}")
        except ApiError as e:
            sys.exit(f"API ERROR {e.status_code}: {e.body}")

        print(f"[{summary.importance_score}/10] {summary.event_type}")
        print(f"tickers : {summary.ticker_tags}")
        print(f"sectors : {summary.sector_tags}")
        print(f"\n{summary.summary_en}\n")

        if client.last_settlement and client.last_settlement.tx_hash:
            tx = client.last_settlement.tx_hash
            print(f"paid: {tx}")
            print(f"https://sepolia.basescan.org/tx/{tx}")
        else:
            print("(no settlement proof — server may have served from free tier)")


if __name__ == "__main__":
    main()
