"""Response models.

Every model uses Pydantic v2 so callers get runtime validation, static
type-checker hints, and ``.model_dump_json()`` for free. Field names
stay in the API's camelCase form because that's what the wire returns —
we do not re-camelcase on the way in.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class Summary(BaseModel):
    """AI-generated English summary of a single DART disclosure.

    This is the payload the paid endpoint returns after a successful
    x402 settlement. ``importance_score`` is 1–10, where 10 is an event
    like M&A or insolvency and 1 is a routine administrative filing.
    """

    model_config = ConfigDict(populate_by_name=True, frozen=True)

    rcpt_no: str = Field(alias="rcptNo")
    # Company identity (server round-18). Korean original + KRX-registered
    # English name; ``report_nm`` is the canonical DART form name and
    # ``report_nm_en`` its English filing-type label. Optional so the SDK
    # stays compatible with older server responses.
    corp_name: Optional[str] = Field(alias="corpName", default=None)
    corp_name_en: Optional[str] = Field(alias="corpNameEn", default=None)
    report_nm: Optional[str] = Field(alias="reportNm", default=None)
    report_nm_en: Optional[str] = Field(alias="reportNmEn", default=None)
    summary_en: str = Field(alias="summaryEn")
    importance_score: int = Field(alias="importanceScore", ge=1, le=10)
    event_type: str = Field(alias="eventType")
    sector_tags: List[str] = Field(alias="sectorTags", default_factory=list)
    ticker_tags: List[str] = Field(alias="tickerTags", default_factory=list)
    actionable_for: List[str] = Field(alias="actionableFor", default_factory=list)
    # Server round-17a: canonical DART viewer link (audit path back to
    # the original filing) and the HIGH/LOW pre-purchase signal for
    # whether this filing class normally carries an extractable number.
    source_url: Optional[str] = Field(alias="sourceUrl", default=None)
    numeric_expectation: Optional[str] = Field(alias="numericExpectation", default=None)
    generated_at: datetime = Field(alias="generatedAt")


class SettlementProof(BaseModel):
    """On-chain settlement proof the API returns in the ``PAYMENT-RESPONSE``
    header (or the legacy ``X-PAYMENT-RESPONSE`` alias for older servers).

    ``tx_hash`` is the Base transaction hash you can look up on
    basescan.org. ``network`` is the CAIP-2 network identifier (e.g.
    ``eip155:84532`` for Base Sepolia, ``eip155:8453`` for Base mainnet).
    ``error_reason`` is populated only when ``success`` is ``False`` —
    the facilitator normally only reaches this path on partial
    settlements, since hard rejects surface as ``PaymentError`` before
    a proof is emitted.
    """

    model_config = ConfigDict(populate_by_name=True, frozen=True)

    success: bool
    tx_hash: Optional[str] = Field(alias="transaction", default=None)
    network: Optional[str] = None
    payer: Optional[str] = None
    error_reason: Optional[str] = Field(alias="errorReason", default=None)


class Pricing(BaseModel):
    """Machine-readable pricing descriptor from ``GET /v1/pricing``."""

    model_config = ConfigDict(populate_by_name=True, frozen=True)

    x402_network: str = Field(alias="x402Network")
    x402_asset: str = Field(alias="x402Asset")
    x402_recipient: str = Field(alias="x402Recipient")
    endpoints: List["PricingEndpoint"]


class PricingEndpoint(BaseModel):
    model_config = ConfigDict(populate_by_name=True, frozen=True)

    method: str
    path: str
    price_usdc: str = Field(alias="priceUsdc")
    description: str


Pricing.model_rebuild()


class Company(BaseModel):
    """A Korean listed company from the KRX directory.

    Returned by the free :meth:`Client.find_company` and
    :meth:`Client.get_company` methods. ``ticker`` is the six-digit KRX
    code; ``corp_code`` is DART's eight-digit internal id (rarely needed
    by an agent but exposed for advanced use cases like cross-referencing
    with raw DART API tools).
    """

    model_config = ConfigDict(populate_by_name=True, frozen=True)

    ticker: str
    corp_code: str = Field(alias="corpCode")
    name_kr: str = Field(alias="nameKr")
    name_en: Optional[str] = Field(alias="nameEn", default=None)
    market: Optional[str] = None


class RecentFiling(BaseModel):
    """Lightweight metadata for a recent DART filing — no AI summary text.

    Returned by the free :meth:`Client.list_recent_filings`. Use the
    ``rcpt_no`` to fetch a single summary via :meth:`Client.get_summary`,
    or the ``ticker`` to fetch a batch via
    :meth:`Client.get_recent_filings`.

    The AI enrichment fields (``importance_score``, ``event_type``,
    ``sector_tags``, ``ticker_tags``, ``actionable_for``) are present
    only when the filing's summary is already cached server-side
    (server round-15b) — they cost nothing extra and let an agent
    rank-order which filings warrant a paid call. ``corp_name_en`` /
    ``report_nm_en`` (round-18) and ``source_url`` /
    ``numeric_expectation`` (round-17a) are populated on every row on
    current servers.
    """

    model_config = ConfigDict(populate_by_name=True, frozen=True)

    rcpt_no: str = Field(alias="rcptNo")
    ticker: Optional[str] = None
    corp_name: str = Field(alias="corpName")
    corp_name_en: Optional[str] = Field(alias="corpNameEn", default=None)
    report_nm: str = Field(alias="reportNm")
    report_nm_en: Optional[str] = Field(alias="reportNmEn", default=None)
    rcept_dt: date = Field(alias="rceptDt")
    importance_score: Optional[int] = Field(alias="importanceScore", default=None, ge=1, le=10)
    event_type: Optional[str] = Field(alias="eventType", default=None)
    sector_tags: Optional[List[str]] = Field(alias="sectorTags", default=None)
    ticker_tags: Optional[List[str]] = Field(alias="tickerTags", default=None)
    actionable_for: Optional[List[str]] = Field(alias="actionableFor", default=None)
    source_url: Optional[str] = Field(alias="sourceUrl", default=None)
    numeric_expectation: Optional[str] = Field(alias="numericExpectation", default=None)
