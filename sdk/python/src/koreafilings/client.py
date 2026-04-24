"""Public client for koreafilings.com.

The only class callers need:

    from koreafilings import Client

    client = Client(private_key="0x...", network="base-sepolia")
    summary = client.get_summary("20260424900874")

The client hides the full x402 flow: it issues the GET, detects the
402 payment prompt, signs an EIP-3009 ``TransferWithAuthorization``,
resubmits the request with the ``X-PAYMENT`` header, and parses the
200 body. Settlement proofs from ``X-PAYMENT-RESPONSE`` are attached to
the returned model via the ``last_settlement`` property.
"""

from __future__ import annotations

from typing import Optional

import httpx
from eth_account import Account

from . import _payment
from .errors import ApiError, ConfigurationError, PaymentError
from .models import Pricing, SettlementProof, Summary


DEFAULT_BASE_URL = "https://api.koreafilings.com"
DEFAULT_TIMEOUT = 60.0

# Maps the short network aliases callers are allowed to pass into the
# constructor to the CAIP-2 identifiers the server uses in its 402
# responses. We only use this for up-front validation — the actual
# network is always whatever the server's 402 body says, so that callers
# never accidentally sign for a different chain than the server expects.
_NETWORK_ALIASES = {
    "base-sepolia": "eip155:84532",
    "base": "eip155:8453",
    "base-mainnet": "eip155:8453",
}


class Client:
    """Blocking HTTP client for the koreafilings paid API.

    Parameters
    ----------
    private_key:
        A 0x-prefixed 32-byte hex string. This key signs the EIP-3009
        authorizations that move USDC from your wallet to the API's
        recipient wallet. **Keep this secret.** The SDK never transmits
        it — signing happens locally, and only the signature goes on the
        wire.
    network:
        Short alias (``"base-sepolia"`` or ``"base"``) used only for
        sanity checks. The authoritative network comes from the 402
        response the server sends; if it disagrees with ``network``,
        ``get_summary`` raises :class:`PaymentError` before signing.
    base_url:
        Override for self-hosted deployments or local development.
        Defaults to ``https://api.koreafilings.com``.
    timeout:
        Per-request timeout in seconds. The paid summary call can take
        up to ~10s on a cold disclosure (LLM latency), so the default
        is generous.
    """

    def __init__(
        self,
        private_key: str,
        network: str = "base-sepolia",
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
    ):
        if not private_key.startswith("0x") or len(private_key) != 66:
            raise ConfigurationError("private_key must be a 0x-prefixed 32-byte hex string (66 chars)")
        if network not in _NETWORK_ALIASES:
            raise ConfigurationError(
                f"unknown network '{network}'; expected one of {sorted(_NETWORK_ALIASES)}"
            )
        self._account = Account.from_key(private_key)
        self._expected_chain = _NETWORK_ALIASES[network]
        self._base_url = base_url.rstrip("/")
        self._http = httpx.Client(timeout=timeout)
        self._last_settlement: Optional[SettlementProof] = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def address(self) -> str:
        """Payer wallet address derived from the configured private key."""
        return self._account.address

    @property
    def last_settlement(self) -> Optional[SettlementProof]:
        """Settlement proof from the most recent successful paid call, if any."""
        return self._last_settlement

    def get_pricing(self) -> Pricing:
        """Fetch the public pricing descriptor. No payment required."""
        resp = self._http.get(f"{self._base_url}/v1/pricing")
        if resp.status_code != 200:
            raise ApiError(resp.status_code, _safe_json(resp))
        return Pricing.model_validate(resp.json())

    def get_summary(self, rcpt_no: str) -> Summary:
        """Fetch the AI summary for a DART receipt number, paying if required.

        The first call for a given ``rcpt_no`` triggers an LLM run on
        the server and costs USDC (0.005 as of v0.1). Every subsequent
        call for the same disclosure is served from the server-side
        cache and still costs the same fee — the cache improves server
        margins, not caller price. Errors map to:

        - :class:`ApiError` for HTTP failures that aren't payment prompts,
        - :class:`PaymentError` if signing/settlement is rejected.
        """
        url = f"{self._base_url}/v1/disclosures/{rcpt_no}/summary"

        unpaid = self._http.get(url)
        if unpaid.status_code == 200:
            # Free/cached response path (the server currently always charges,
            # but a future free-tier could land here without breaking callers).
            self._last_settlement = None
            return Summary.model_validate(unpaid.json())
        if unpaid.status_code != 402:
            raise ApiError(unpaid.status_code, _safe_json(unpaid))

        body = _safe_json(unpaid) or {}
        requirement = _payment.select_requirement(body.get("accepts") or [])

        advertised = requirement.get("network")
        if advertised != self._expected_chain:
            raise PaymentError(
                reason="network_mismatch",
                detail=f"server advertises {advertised}, client configured for {self._expected_chain}",
            )

        authorization = _payment.build_authorization(self._account.address, requirement)
        signature = _payment.sign_eip3009(self._account, requirement, authorization)
        header_value = _payment.build_x_payment_header(url, requirement, authorization, signature)

        paid = self._http.get(url, headers={"X-PAYMENT": header_value})
        if paid.status_code == 402:
            # Facilitator rejected after we signed — bubble up its reason.
            rejection = _safe_json(paid) or {}
            raise PaymentError(
                reason=rejection.get("error") or "payment_rejected",
                detail=rejection,
            )
        if paid.status_code != 200:
            raise ApiError(paid.status_code, _safe_json(paid))

        settlement_raw = _payment.decode_settlement_header(paid.headers.get("X-PAYMENT-RESPONSE"))
        self._last_settlement = (
            SettlementProof.model_validate(settlement_raw) if settlement_raw else None
        )
        return Summary.model_validate(paid.json())

    def close(self) -> None:
        """Close the underlying HTTP connection pool."""
        self._http.close()

    # ------------------------------------------------------------------
    # Context manager support
    # ------------------------------------------------------------------

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()


def _safe_json(resp: httpx.Response) -> Optional[dict]:
    """Best-effort JSON parse; returns None on any failure."""
    try:
        return resp.json()
    except Exception:  # noqa: BLE001
        return None
