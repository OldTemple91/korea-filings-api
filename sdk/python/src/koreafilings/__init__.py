"""koreafilings — Python SDK for AI-summarized Korean DART disclosures.

Minimal usage:

    from koreafilings import Client

    with Client(private_key="0x...", network="base") as client:
        # 1. Free name → ticker resolution
        matches = client.find_company("Samsung Electronics")
        ticker = matches[0].ticker  # "005930"

        # 2. Paid batch summary fetch
        filings = client.get_recent_filings(ticker, limit=5)
        for f in filings:
            print(f.importance_score, f.event_type, f.summary_en)
        print("paid:", client.last_settlement.tx_hash)
"""

from .client import Client
from .errors import ApiError, ConfigurationError, KoreaFilingsError, PaymentError
from .models import (
    Company,
    Pricing,
    PricingEndpoint,
    RecentFiling,
    SettlementProof,
    Summary,
)

__all__ = [
    "Client",
    "Company",
    "RecentFiling",
    "Summary",
    "SettlementProof",
    "Pricing",
    "PricingEndpoint",
    "KoreaFilingsError",
    "ApiError",
    "PaymentError",
    "ConfigurationError",
]

__version__ = "0.3.1"
