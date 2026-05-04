#!/usr/bin/env python3
"""
x402 test client for korea-filings-api (v2 transport spec).

Usage (from repo root):
    uv run testclient/payer.py
    # or with explicit rcpt_no:
    uv run testclient/payer.py 20260423000001

Reads PAYER_PRIVATE_KEY, API_BASE_URL, TARGET_RCPT_NO from
testclient/.env.testclient (copy .env.testclient.example and fill in).

Flow:
    1. GET /v1/disclosures/summary?rcptNo={rcpt_no}  (no payment header)
       -> 402 with accepts[] (and PAYMENT-REQUIRED header per v2 spec)
    2. Build an EIP-3009 TransferWithAuthorization for accepts[0]
    3. Sign via EIP-712 with PAYER_PRIVATE_KEY
    4. Base64-encode the signed PaymentPayload into PAYMENT-SIGNATURE
    5. Retry the same GET -> 200 + summary body + PAYMENT-RESPONSE header
       (X-PAYMENT-RESPONSE alias also still emitted for v1 clients)
    6. Decode and print the settlement proof

Real Gemini summaries get paid for with real Base-Sepolia USDC from
the payer wallet to the recipient wallet. The facilitator (Coinbase
CDP by default) covers the gas.
"""

from __future__ import annotations

import base64
import json
import os
import secrets
import sys
import time
from pathlib import Path

import requests
from dotenv import load_dotenv
from eth_account import Account
from eth_account.messages import encode_typed_data


def load_env() -> dict:
    env_path = Path(__file__).parent / ".env.testclient"
    if not env_path.exists():
        sys.exit(f"Missing {env_path}. Copy .env.testclient.example and fill it in.")
    load_dotenv(env_path)

    pk = os.environ.get("PAYER_PRIVATE_KEY", "").strip()
    if not pk.startswith("0x") or len(pk) != 66:
        sys.exit("PAYER_PRIVATE_KEY must be a 0x-prefixed 32-byte hex string (66 chars).")
    return {
        "pk": pk,
        "base_url": os.environ.get("API_BASE_URL", "http://localhost:8080").rstrip("/"),
        "rcpt_no": os.environ.get("TARGET_RCPT_NO", "20260423000001"),
    }


def fetch_payment_requirements(url: str) -> dict:
    resp = requests.get(url, timeout=15)
    if resp.status_code != 402:
        sys.exit(
            f"Expected 402 on first call, got {resp.status_code}:\n{resp.text}"
        )
    body = resp.json()
    print(f"[402] Payment required:\n{json.dumps(body, indent=2, ensure_ascii=False)}\n")
    return body


def build_authorization(payer_address: str, requirement: dict) -> dict:
    now = int(time.time())
    return {
        "from": payer_address,
        "to": requirement["payTo"],
        "value": requirement["amount"],
        # 10 s of backfill to tolerate minor clock skew vs the facilitator.
        "validAfter": str(max(0, now - 10)),
        "validBefore": str(now + int(requirement["maxTimeoutSeconds"])),
        "nonce": "0x" + secrets.token_hex(32),
    }


def sign_eip3009(account: Account, requirement: dict, authorization: dict) -> str:
    """EIP-712 signature for an ERC-3009 TransferWithAuthorization."""
    chain_id = int(requirement["network"].split(":")[1])
    extra = requirement.get("extra") or {}
    typed = {
        "types": {
            "EIP712Domain": [
                {"name": "name", "type": "string"},
                {"name": "version", "type": "string"},
                {"name": "chainId", "type": "uint256"},
                {"name": "verifyingContract", "type": "address"},
            ],
            "TransferWithAuthorization": [
                {"name": "from", "type": "address"},
                {"name": "to", "type": "address"},
                {"name": "value", "type": "uint256"},
                {"name": "validAfter", "type": "uint256"},
                {"name": "validBefore", "type": "uint256"},
                {"name": "nonce", "type": "bytes32"},
            ],
        },
        "primaryType": "TransferWithAuthorization",
        "domain": {
            "name": extra.get("name", "USDC"),
            "version": extra.get("version", "2"),
            "chainId": chain_id,
            "verifyingContract": requirement["asset"],
        },
        "message": {
            "from": authorization["from"],
            "to": authorization["to"],
            "value": int(authorization["value"]),
            "validAfter": int(authorization["validAfter"]),
            "validBefore": int(authorization["validBefore"]),
            "nonce": authorization["nonce"],
        },
    }
    signable = encode_typed_data(full_message=typed)
    signed = account.sign_message(signable)
    hex_sig = signed.signature.hex()
    return hex_sig if hex_sig.startswith("0x") else f"0x{hex_sig}"


def build_payment_signature_header(
    resource_url: str, requirement: dict, authorization: dict, signature: str
) -> str:
    """Base64-encode the signed PaymentPayload for the PAYMENT-SIGNATURE header.

    The wire format is unchanged from v1; only the HTTP header name
    moved from X-PAYMENT to PAYMENT-SIGNATURE in the v2 transport spec.
    """
    payload = {
        "x402Version": 2,
        "resource": {
            "url": resource_url,
            "description": "DART summary",
            "mimeType": "application/json",
        },
        "accepted": requirement,
        "payload": {"signature": signature, "authorization": authorization},
    }
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def main() -> None:
    env = load_env()
    rcpt_no = sys.argv[1] if len(sys.argv) > 1 else env["rcpt_no"]
    summary_url = f"{env['base_url']}/v1/disclosures/summary?rcptNo={rcpt_no}"

    account = Account.from_key(env["pk"])
    print(f"Payer address : {account.address}")
    print(f"Target        : {summary_url}\n")

    body = fetch_payment_requirements(summary_url)
    if not body.get("accepts"):
        sys.exit("402 body has no 'accepts' list — cannot proceed.")
    requirement = body["accepts"][0]

    authorization = build_authorization(account.address, requirement)
    signature = sign_eip3009(account, requirement, authorization)
    payment_header = build_payment_signature_header(
        summary_url, requirement, authorization, signature
    )
    print(
        f"[SIGNED] nonce={authorization['nonce'][:14]}…  "
        f"value={requirement['amount']}  sig_len={len(signature)}\n"
    )

    resp = requests.get(
        summary_url,
        headers={"PAYMENT-SIGNATURE": payment_header},
        timeout=60,
    )
    print(f"[RETRY] status={resp.status_code}")

    settlement_header = resp.headers.get("PAYMENT-RESPONSE") or resp.headers.get(
        "X-PAYMENT-RESPONSE"
    )
    if settlement_header:
        try:
            decoded = json.loads(base64.b64decode(settlement_header))
            print(f"[SETTLED]\n{json.dumps(decoded, indent=2)}\n")
        except Exception as e:
            print(f"[WARN] settlement header present but failed to decode: {e}\n")
    else:
        print("[WARN] No PAYMENT-RESPONSE header — settlement unconfirmed.\n")

    if resp.status_code == 200:
        print(
            "[SUMMARY]\n"
            + json.dumps(resp.json(), indent=2, ensure_ascii=False)
        )
    elif resp.status_code == 502:
        print(
            "[FAIL-CLOSED] settlement rejected; data was withheld:\n"
            + resp.text
        )
    else:
        print(f"[ERROR] {resp.status_code} body:\n{resp.text}")


if __name__ == "__main__":
    main()
