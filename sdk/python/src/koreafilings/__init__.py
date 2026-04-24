"""koreafilings — Python SDK for AI-summarized Korean DART disclosures.

Minimal usage:

    from koreafilings import Client

    with Client(private_key="0x...", network="base-sepolia") as client:
        summary = client.get_summary("20260424900874")
        print(summary.importance_score, summary.summary_en)
        print("paid tx:", client.last_settlement.tx_hash)
"""

from .client import Client
from .errors import ApiError, ConfigurationError, KoreaFilingsError, PaymentError
from .models import Pricing, PricingEndpoint, SettlementProof, Summary

__all__ = [
    "Client",
    "Summary",
    "SettlementProof",
    "Pricing",
    "PricingEndpoint",
    "KoreaFilingsError",
    "ApiError",
    "PaymentError",
    "ConfigurationError",
]

__version__ = "0.1.0"
