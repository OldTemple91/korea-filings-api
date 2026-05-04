"""Public client for koreafilings.com.

The only class callers need:

    from koreafilings import Client

    client = Client(private_key="0x...", network="base-sepolia")
    summary = client.get_summary("20260424900874")

The client hides the full x402 flow: it issues the GET, detects the
402 payment prompt, signs an EIP-3009 ``TransferWithAuthorization``,
resubmits the request with the ``PAYMENT-SIGNATURE`` header (x402 v2
transport spec), and parses the 200 body. Settlement proofs from the
``PAYMENT-RESPONSE`` header (or the legacy ``X-PAYMENT-RESPONSE``
alias) are attached to the returned model via the ``last_settlement``
property.

Paid endpoints accept their inputs as query parameters since 0.3.0
(``?rcptNo=...`` and ``?ticker=...&limit=N``). Earlier 0.2.x releases
used path parameters; the server still accepts both shapes.
"""

from __future__ import annotations

from typing import Any, List, Optional

import httpx
from eth_account import Account

from . import _payment
from .errors import ApiError, ConfigurationError, PaymentError
from .models import Company, Pricing, RecentFiling, SettlementProof, Summary


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

    # ------------------------------------------------------------------
    # Free discovery (v1.1)
    # ------------------------------------------------------------------

    def find_company(self, query: str, limit: int = 20) -> List[Company]:
        """Search the KRX listed-company directory by name or ticker. Free.

        Use this as the first step in an agent flow when you have a
        company name (English or Korean) but not the ticker. Pass the
        resulting ``ticker`` to :meth:`get_recent_filings` to fetch
        paid AI summaries.

        ``limit`` is capped at 50 server-side; passing more is a no-op.
        """
        resp = self._http.get(
            f"{self._base_url}/v1/companies",
            params={"q": query, "limit": min(max(limit, 1), 50)},
        )
        if resp.status_code != 200:
            raise ApiError(resp.status_code, _safe_json(resp))
        body = resp.json() or {}
        return [Company.model_validate(m) for m in body.get("matches", [])]

    def get_company(self, ticker: str) -> Optional[Company]:
        """Fetch a single company by ticker, or ``None`` if unknown. Free."""
        resp = self._http.get(f"{self._base_url}/v1/companies/{ticker}")
        if resp.status_code == 404:
            return None
        if resp.status_code != 200:
            raise ApiError(resp.status_code, _safe_json(resp))
        return Company.model_validate(resp.json())

    def list_recent_filings(
            self,
            limit: int = 20,
            since_hours: int = 24,
    ) -> List[RecentFiling]:
        """Browse recent DART filings across every listed company. Free.

        Returns metadata only — no AI summaries. Use this to discover
        what is happening today, then call :meth:`get_recent_filings`
        or :meth:`get_summary` to pay for the summaries you actually
        want.
        """
        resp = self._http.get(
            f"{self._base_url}/v1/disclosures/recent",
            params={
                "limit": min(max(limit, 1), 100),
                "since_hours": min(max(since_hours, 1), 168),
            },
        )
        if resp.status_code != 200:
            raise ApiError(resp.status_code, _safe_json(resp))
        body = resp.json() or {}
        return [RecentFiling.model_validate(f) for f in body.get("filings", [])]

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
        url = f"{self._base_url}/v1/disclosures/summary"
        body = self._paid_get(url, params={"rcptNo": rcpt_no})
        return Summary.model_validate(body)

    def get_recent_filings(self, ticker: str, limit: int = 5) -> List[Summary]:
        """Fetch up to ``limit`` AI summaries for one Korean ticker.

        Costs ``0.005 × limit`` USDC, settled in a single x402 payment.
        Returns up to ``limit`` summaries, newest first; if the company
        has fewer recent filings than requested the response is shorter
        and the agent has overpaid for the missing slots — pre-filter
        with :meth:`list_recent_filings` if budget is tight.

        ``limit`` is capped at 50 server-side; passing more is rejected
        with :class:`ApiError`.
        """
        url = f"{self._base_url}/v1/disclosures/by-ticker"
        body = self._paid_get(url, params={
            "ticker": ticker,
            "limit": min(max(limit, 1), 50),
        })
        return [Summary.model_validate(s) for s in body.get("summaries", [])]

    # ------------------------------------------------------------------
    # Paid-call helper
    # ------------------------------------------------------------------

    def _paid_get(self, url: str, params: Optional[dict]) -> Any:
        """Issue a paid GET, signing the EIP-3009 authorization on demand.

        Centralises the 402 → sign → retry flow so every paid endpoint
        shares the same error handling, network mismatch check, and
        settlement-proof attachment.
        """
        unpaid = self._http.get(url, params=params)
        if unpaid.status_code == 200:
            # Server-issued free response — preserved for forward
            # compatibility with future tiers.
            self._last_settlement = None
            return unpaid.json()
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
        # The signed resource URL must be the exact request URL the
        # server will see, including any query string. httpx's
        # `request.url` after the unpaid call captures that for us.
        full_url = str(unpaid.request.url) if unpaid.request else url
        header_value = _payment.build_payment_signature_header(
            full_url, requirement, authorization, signature
        )

        # x402 v2 transport spec: PAYMENT-SIGNATURE (request), PAYMENT-RESPONSE
        # (settlement). The server still accepts X-PAYMENT for older clients,
        # but new SDK installs always send the v2 name.
        paid = self._http.get(url, params=params, headers={"PAYMENT-SIGNATURE": header_value})

        settlement_header = (
            paid.headers.get("PAYMENT-RESPONSE")
            or paid.headers.get("X-PAYMENT-RESPONSE")
        )
        settlement_raw = _payment.decode_settlement_header(settlement_header)

        if paid.status_code == 402:
            # Per the x402 v2 transport spec, a 402 on the retry call
            # carries the failure outcome in PAYMENT-RESPONSE: the
            # facilitator either rejected /verify, the signature was
            # already used, or /settle failed after a successful
            # verify. Either way, the body is empty and the
            # SettlementResponse header tells the caller why so they
            # can decide whether to re-sign.
            if settlement_raw and settlement_raw.get("success") is False:
                raise PaymentError(
                    reason=settlement_raw.get("errorReason") or "settle_failed",
                    detail=settlement_raw,
                )
            rejection = _safe_json(paid) or {}
            raise PaymentError(
                reason=rejection.get("error") or "payment_rejected",
                detail=rejection,
            )
        if paid.status_code != 200:
            raise ApiError(paid.status_code, _safe_json(paid))
        self._last_settlement = (
            SettlementProof.model_validate(settlement_raw) if settlement_raw else None
        )
        return paid.json()

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
