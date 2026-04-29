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
    summary_en: str = Field(alias="summaryEn")
    importance_score: int = Field(alias="importanceScore", ge=1, le=10)
    event_type: str = Field(alias="eventType")
    sector_tags: List[str] = Field(alias="sectorTags", default_factory=list)
    ticker_tags: List[str] = Field(alias="tickerTags", default_factory=list)
    actionable_for: List[str] = Field(alias="actionableFor", default_factory=list)
    generated_at: datetime = Field(alias="generatedAt")


class SettlementProof(BaseModel):
    """On-chain settlement proof the API returns via ``X-PAYMENT-RESPONSE``.

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
    """Lightweight metadata for a recent DART filing — no AI summary.

    Returned by the free :meth:`Client.list_recent_filings`. Use the
    ``rcpt_no`` to fetch a single summary via :meth:`Client.get_summary`,
    or the ``ticker`` to fetch a batch via
    :meth:`Client.get_recent_filings`.
    """

    model_config = ConfigDict(populate_by_name=True, frozen=True)

    rcpt_no: str = Field(alias="rcptNo")
    ticker: Optional[str] = None
    corp_name: str = Field(alias="corpName")
    report_nm: str = Field(alias="reportNm")
    rcept_dt: date = Field(alias="rceptDt")
