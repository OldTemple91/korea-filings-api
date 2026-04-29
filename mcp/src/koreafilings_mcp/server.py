"""MCP server entry point.

Exposes koreafilings.com's paid DART summary API to any MCP client
(Claude Desktop, Cursor, Continue, …) via stdio transport. The
underlying HTTP + x402 flow is delegated entirely to the
``koreafilings`` SDK — this module is the protocol bridge and nothing
more.

Environment:

- ``KOREAFILINGS_PRIVATE_KEY`` — **required for paid tools.** A
  0x-prefixed 32-byte hex string that signs EIP-3009 authorizations
  against USDC. Free tools (``get_pricing``) work without it.
- ``KOREAFILINGS_NETWORK`` — ``"base-sepolia"`` (default) or ``"base"``.
- ``KOREAFILINGS_BASE_URL`` — override for self-hosted or local dev.
  Defaults to ``https://api.koreafilings.com``.
"""

from __future__ import annotations

import os
from typing import Any

from mcp.server.fastmcp import FastMCP

from koreafilings import ApiError, Client, ConfigurationError, PaymentError


mcp = FastMCP(
    "koreafilings",
    instructions=(
        "Tools for querying koreafilings.com, a commercial API that serves "
        "AI-generated English summaries of Korean DART (금융감독원 전자공시) "
        "corporate disclosures. Paid tools settle on-chain in USDC via the "
        "x402 payment protocol; the user's private key must be configured "
        "in the client's MCP server environment before calling them."
    ),
)


_client: Client | None = None


def _get_client() -> Client:
    """Return the process-singleton Client, creating it on first use.

    Lazy construction means the server can start (and ``get_pricing``
    can run) even when the payer private key is absent. Only paid tools
    trigger the real initialisation.
    """
    global _client
    if _client is not None:
        return _client

    pk = os.environ.get("KOREAFILINGS_PRIVATE_KEY", "").strip()
    if not pk:
        raise RuntimeError(
            "KOREAFILINGS_PRIVATE_KEY is not set. Add it to this MCP "
            "server's environment in your Claude Desktop / Cursor / "
            "Continue config before invoking paid tools."
        )
    network = os.environ.get("KOREAFILINGS_NETWORK", "base-sepolia").strip() or "base-sepolia"
    base_url = os.environ.get("KOREAFILINGS_BASE_URL", "https://api.koreafilings.com").strip()

    try:
        _client = Client(private_key=pk, network=network, base_url=base_url)
    except ConfigurationError as e:
        raise RuntimeError(f"koreafilings SDK refused the MCP config: {e}") from e
    return _client


# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------


@mcp.tool()
def get_pricing() -> dict[str, Any]:
    """Fetch the current per-endpoint pricing for koreafilings.com.

    This is a free call; it returns the x402 wallet address, network,
    USDC contract, and the price in USDC for each paid endpoint. Useful
    to confirm the payer will be settling on the expected chain before
    spending anything.
    """
    network = os.environ.get("KOREAFILINGS_NETWORK", "base-sepolia").strip() or "base-sepolia"
    base_url = os.environ.get("KOREAFILINGS_BASE_URL", "https://api.koreafilings.com").strip()
    probe = Client(private_key="0x" + "00" * 32, network=network, base_url=base_url)
    try:
        pricing = probe.get_pricing()
    finally:
        probe.close()
    return pricing.model_dump(by_alias=True)


@mcp.tool()
def find_company(query: str, limit: int = 20) -> list[dict[str, Any]]:
    """Search the KRX directory of Korean listed companies. Free.

    Use this as the first step when you have a company name (English
    or Korean) but not the six-digit KRX ticker. Pass the resulting
    ticker to ``get_recent_filings`` (paid) or ``get_disclosure_summary``
    (paid, when you also have a specific receipt number).

    Args:
        query: Company name (English or Korean) or six-digit ticker.
            Examples: "Samsung Electronics", "삼성전자", "005930".
        limit: Max matches to return (1-50, default 20).

    Returns:
        A list of company dicts with ``ticker``, ``corp_code``,
        ``name_kr``, ``name_en``, and ``market`` (KOSPI / KOSDAQ).
        Empty list when nothing matches; never raises on no-results.
    """
    network = os.environ.get("KOREAFILINGS_NETWORK", "base-sepolia").strip() or "base-sepolia"
    base_url = os.environ.get("KOREAFILINGS_BASE_URL", "https://api.koreafilings.com").strip()
    probe = Client(private_key="0x" + "00" * 32, network=network, base_url=base_url)
    try:
        matches = probe.find_company(query, limit=limit)
    finally:
        probe.close()
    return [
        {
            "ticker": m.ticker,
            "corp_code": m.corp_code,
            "name_kr": m.name_kr,
            "name_en": m.name_en,
            "market": m.market,
        }
        for m in matches
    ]


@mcp.tool()
def list_recent_filings(limit: int = 20, since_hours: int = 24) -> list[dict[str, Any]]:
    """Browse recent DART filings across every listed Korean company. Free.

    Returns metadata only — no AI summaries — so an agent can decide
    which filings warrant a paid call. Each entry includes ``rcpt_no``
    (for ``get_disclosure_summary``) and ``ticker`` (for
    ``get_recent_filings``).

    Args:
        limit: Max filings to return (1-100, default 20).
        since_hours: Look back this many hours (1-168, default 24).

    Returns:
        A list of filing-metadata dicts.
    """
    network = os.environ.get("KOREAFILINGS_NETWORK", "base-sepolia").strip() or "base-sepolia"
    base_url = os.environ.get("KOREAFILINGS_BASE_URL", "https://api.koreafilings.com").strip()
    probe = Client(private_key="0x" + "00" * 32, network=network, base_url=base_url)
    try:
        filings = probe.list_recent_filings(limit=limit, since_hours=since_hours)
    finally:
        probe.close()
    return [
        {
            "rcpt_no": f.rcpt_no,
            "ticker": f.ticker,
            "corp_name": f.corp_name,
            "report_nm": f.report_nm,
            "rcept_dt": f.rcept_dt.isoformat(),
            "dart_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={f.rcpt_no}",
        }
        for f in filings
    ]


@mcp.tool()
def get_recent_filings(ticker: str, limit: int = 5) -> dict[str, Any]:
    """Fetch up to ``limit`` AI summaries for one Korean ticker.

    **This tool spends real USDC from the configured wallet** — 0.005
    USDC × ``limit`` per call (default 0.025 USDC). The wallet pays
    only on a successful 200 response; 4xx/5xx failures do not settle.

    If you only have a company name, call ``find_company`` first to
    resolve the ticker.

    Args:
        ticker: Six-digit KRX ticker, e.g. "005930" for Samsung Electronics.
        limit: Max filings to fetch (1-50, default 5). Each costs 0.005 USDC.

    Returns:
        A dict with ``ticker``, ``count``, ``summaries`` (each summary
        carries the same shape as ``get_disclosure_summary``), and a
        ``payment`` block with the on-chain settlement tx hash.

    Raises:
        RuntimeError: on payment rejection or API failure.
    """
    client = _get_client()
    try:
        summaries = client.get_recent_filings(ticker, limit=limit)
    except PaymentError as e:
        raise RuntimeError(f"payment failed: {e}") from e
    except ApiError as e:
        raise RuntimeError(f"api returned {e.status_code}: {e.body}") from e

    payload: dict[str, Any] = {
        "ticker": ticker,
        "count": len(summaries),
        "summaries": [
            {
                "rcpt_no": s.rcpt_no,
                "summary_en": s.summary_en,
                "importance_score": s.importance_score,
                "event_type": s.event_type,
                "sector_tags": list(s.sector_tags),
                "ticker_tags": list(s.ticker_tags),
                "actionable_for": list(s.actionable_for),
                "generated_at": s.generated_at.isoformat(),
                "dart_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={s.rcpt_no}",
            }
            for s in summaries
        ],
    }
    settlement = client.last_settlement
    if settlement and settlement.tx_hash:
        payload["payment"] = {
            "tx_hash": settlement.tx_hash,
            "network": settlement.network,
            "payer": settlement.payer,
            "explorer_url": _explorer_url(settlement.network, settlement.tx_hash),
        }
    return payload


@mcp.tool()
def get_disclosure_summary(rcpt_no: str) -> dict[str, Any]:
    """Fetch the AI-generated English summary of a Korean DART disclosure.

    **This tool spends real USDC from the configured wallet** — 0.005
    USDC per call as of v0.1, settled on-chain via x402. The wallet
    pays only on a successful 200 response; 4xx/5xx failures do not
    settle.

    Args:
        rcpt_no: 14-digit DART receipt number, e.g. ``"20260424900874"``.
            You can discover receipt numbers from the DART portal at
            https://dart.fss.or.kr/ or from koreafilings.com's listing
            endpoints as they come online.

    Returns:
        A dict with the summary content (``summary_en``), operational
        metadata (``importance_score`` 1–10, ``event_type``,
        ``ticker_tags``, ``sector_tags``, ``actionable_for``,
        ``generated_at``), and payment proof (``paid_tx``, ``network``,
        ``payer``). If the server served from its free-tier path the
        payment block is absent.

    Raises:
        RuntimeError: when the SDK rejects the request. The message
            distinguishes payment failures (facilitator rejection,
            network mismatch, insufficient balance) from other API
            errors (404 unknown rcpt_no, 429 rate limit, 5xx upstream).
    """
    client = _get_client()
    try:
        summary = client.get_summary(rcpt_no)
    except PaymentError as e:
        raise RuntimeError(f"payment failed: {e}") from e
    except ApiError as e:
        raise RuntimeError(f"api returned {e.status_code}: {e.body}") from e

    payload: dict[str, Any] = {
        "rcpt_no": summary.rcpt_no,
        "summary_en": summary.summary_en,
        "importance_score": summary.importance_score,
        "event_type": summary.event_type,
        "sector_tags": list(summary.sector_tags),
        "ticker_tags": list(summary.ticker_tags),
        "actionable_for": list(summary.actionable_for),
        "generated_at": summary.generated_at.isoformat(),
        "dart_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={summary.rcpt_no}",
    }
    settlement = client.last_settlement
    if settlement and settlement.tx_hash:
        payload["payment"] = {
            "tx_hash": settlement.tx_hash,
            "network": settlement.network,
            "payer": settlement.payer,
            "explorer_url": _explorer_url(settlement.network, settlement.tx_hash),
        }
    return payload


def _explorer_url(network: str | None, tx_hash: str) -> str:
    """Map a CAIP-2 network id to the matching block-explorer URL."""
    match network:
        case "eip155:8453":
            return f"https://basescan.org/tx/{tx_hash}"
        case "eip155:84532":
            return f"https://sepolia.basescan.org/tx/{tx_hash}"
        case _:
            return f"https://basescan.org/tx/{tx_hash}"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    """Console-script entry point — runs the server over stdio."""
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
