"""Exception hierarchy for the koreafilings SDK.

Callers catch ``KoreaFilingsError`` to handle any SDK-level failure, or
a more specific subclass when they care to distinguish payment issues
from transport issues.
"""

from __future__ import annotations

from typing import Any, Mapping


class KoreaFilingsError(Exception):
    """Base class for every error this SDK raises."""


class ApiError(KoreaFilingsError):
    """The API returned a non-2xx response that is not a 402 payment prompt.

    Typical causes: 404 (unknown rcpt_no), 429 (rate limit), 5xx (upstream
    DART or LLM transient failure). ``status_code`` is the HTTP status;
    ``body`` is the raw JSON body if the server supplied one, else ``None``.
    """

    def __init__(self, status_code: int, body: Any | None, message: str | None = None):
        self.status_code = status_code
        self.body = body
        suffix = f": {message}" if message else ""
        super().__init__(f"API returned {status_code}{suffix}")


class PaymentError(KoreaFilingsError):
    """The facilitator or the API rejected the x402 payment attempt.

    Raised when a signed payment is rejected by the facilitator (bad
    signature, insufficient balance, expired authorization) or when the
    API replies 402 a second time after we submitted a payment header.
    ``reason`` is the short code returned by the facilitator when one
    is available.
    """

    def __init__(self, reason: str | None, detail: Mapping[str, Any] | str | None = None):
        self.reason = reason
        self.detail = detail
        parts = [p for p in (reason, str(detail) if detail else None) if p]
        super().__init__(" — ".join(parts) if parts else "payment rejected")


class ConfigurationError(KoreaFilingsError):
    """The SDK was constructed with invalid arguments.

    Raised eagerly at construction time so bad configs never reach the
    first HTTP call. Examples: malformed private key, unknown network
    alias.
    """
