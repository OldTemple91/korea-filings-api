"""x402 payment internals.

This module is intentionally underscore-prefixed. It is not part of the
public SDK surface — callers should reach it only via the public
``Client`` class.

What lives here:

- parsing the 402 "accepts" block the API returns,
- building the EIP-3009 TransferWithAuthorization message,
- signing it with the caller's private key (EIP-712),
- serialising the signed ``PaymentPayload`` as the base64
  ``PAYMENT-SIGNATURE`` header the x402 v2 transport spec expects.

We deliberately do not talk to the facilitator from the client. The
server's interceptor submits the signed payment to the facilitator,
which keeps the SDK small and avoids leaking facilitator URL choices
into user code.
"""

from __future__ import annotations

import base64
import json
import secrets
import time
from typing import Any, Mapping

from eth_account import Account
from eth_account.messages import encode_typed_data

from .errors import PaymentError


X402_VERSION = 2


def select_requirement(accepts: list[Mapping[str, Any]]) -> Mapping[str, Any]:
    """Pick the first payment requirement the server offers.

    The 402 body carries a list of acceptable payment schemes. Today the
    server only ever offers one (USDC on whatever network it's configured
    for), so we take index 0. If the server starts offering multiple
    schemes, this is the place to prefer the cheaper one.
    """
    if not accepts:
        raise PaymentError(reason="empty_accepts", detail="server returned 402 with no payment options")
    return accepts[0]


def build_authorization(payer_address: str, requirement: Mapping[str, Any]) -> dict:
    """Assemble the EIP-3009 authorization fields.

    ``validAfter`` gets a 10-second backfill so a payer clock that is
    slightly behind the facilitator's doesn't cause a rejection.
    ``validBefore`` uses the ``maxTimeoutSeconds`` the server advertised.
    ``nonce`` is random 32 bytes — the facilitator rejects replays.
    """
    now = int(time.time())
    return {
        "from": payer_address,
        "to": requirement["payTo"],
        "value": requirement["amount"],
        "validAfter": str(max(0, now - 10)),
        "validBefore": str(now + int(requirement["maxTimeoutSeconds"])),
        "nonce": "0x" + secrets.token_hex(32),
    }


def sign_eip3009(account: Account, requirement: Mapping[str, Any], authorization: Mapping[str, Any]) -> str:
    """Produce the 65-byte EIP-712 signature over the authorization.

    The EIP-712 domain ``name`` and ``version`` come from the server's
    402 response (``requirement.extra``). A wrong domain causes the
    on-chain ``transferWithAuthorization`` to revert with an opaque
    error and consumes the signature's nonce, so a malicious or
    misconfigured server could trick the SDK into burning nonces by
    sending a domain that doesn't match any deployed USDC contract.

    To make this visible, warn (not hard-fail, to avoid breaking
    forward compat with future networks) when the declared
    ``extra.name`` is not one of the two USDC values we know:
    ``"USD Coin"`` (Base mainnet) or ``"USDC"`` (Base Sepolia).
    """
    chain_id = int(requirement["network"].split(":")[1])
    extra = requirement.get("extra") or {}
    domain_name = extra.get("name", "USDC")
    domain_version = extra.get("version", "2")
    if domain_name not in ("USD Coin", "USDC"):
        import warnings
        warnings.warn(
            f"x402: server-declared EIP-712 token name {domain_name!r} is not "
            "the canonical 'USD Coin' (Base mainnet) or 'USDC' (Base Sepolia). "
            "If the server is genuine, verify the asset contract; if not, the "
            "wrong domain will burn the EIP-3009 nonce on-chain.",
            stacklevel=2,
        )
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
            "name": domain_name,
            "version": domain_version,
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
    resource_url: str,
    requirement: Mapping[str, Any],
    authorization: Mapping[str, Any],
    signature: str,
) -> str:
    """Base64-encode the signed payload into the ``PAYMENT-SIGNATURE`` header value.

    Wire format is identical to the v1 ``X-PAYMENT`` header — only the
    HTTP header name changed in the x402 v2 transport spec.
    """
    payload = {
        "x402Version": X402_VERSION,
        "resource": {
            "url": resource_url,
            "description": requirement.get("description", ""),
            "mimeType": "application/json",
        },
        "accepted": requirement,
        "payload": {"signature": signature, "authorization": authorization},
    }
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


# Backwards-compatible alias for any external code that imported the
# v0.2.x name. New call sites use ``build_payment_signature_header``.
build_x_payment_header = build_payment_signature_header


def decode_settlement_header(value: str | None) -> dict | None:
    """Decode the settlement proof header, or return None.

    Servers running x402 v2 transport spec emit ``PAYMENT-RESPONSE``;
    older servers emit ``X-PAYMENT-RESPONSE``. Either header carries
    the same base64-encoded JSON payload, so this function does not
    care which one the caller pulled off the response.
    """
    if not value:
        return None
    try:
        return json.loads(base64.b64decode(value))
    except Exception:  # noqa: BLE001 — any decode failure returns None
        return None
