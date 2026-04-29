"""Unit tests covering the SDK pieces that do not require the network.

Integration against a live server happens out-of-band (the repo ships a
separate live-payer script). These tests focus on:

- constructor argument validation,
- payment header construction and signature shape,
- response model parsing.
"""

from __future__ import annotations

import base64
import json

import pytest
from eth_account import Account

from koreafilings import (
    ApiError,
    Client,
    Company,
    ConfigurationError,
    PaymentError,
    RecentFiling,
    SettlementProof,
    Summary,
)
from koreafilings._payment import (
    build_authorization,
    build_x_payment_header,
    decode_settlement_header,
    select_requirement,
    sign_eip3009,
)


# A throwaway, publicly-known test key — never funded. Only used to drive
# signature paths in these tests.
TEST_KEY = "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"


def test_client_rejects_malformed_private_key():
    with pytest.raises(ConfigurationError):
        Client(private_key="not_a_key")


def test_client_rejects_unknown_network():
    with pytest.raises(ConfigurationError):
        Client(private_key=TEST_KEY, network="ethereum-mainnet")


def test_client_accepts_valid_config():
    client = Client(private_key=TEST_KEY, network="base-sepolia")
    assert client.address.startswith("0x")
    assert len(client.address) == 42
    client.close()


def test_select_requirement_empty_raises():
    with pytest.raises(PaymentError):
        select_requirement([])


def test_build_authorization_has_required_fields():
    req = {
        "payTo": "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
        "amount": "5000",
        "maxTimeoutSeconds": 60,
    }
    auth = build_authorization("0xabc0000000000000000000000000000000000001", req)
    assert auth["to"] == req["payTo"]
    assert auth["value"] == "5000"
    assert auth["nonce"].startswith("0x") and len(auth["nonce"]) == 66
    assert int(auth["validBefore"]) > int(auth["validAfter"])


def test_sign_eip3009_produces_65_byte_hex_signature():
    account = Account.from_key(TEST_KEY)
    req = {
        "network": "eip155:84532",
        "asset": "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
        "payTo": "0x8467Be164C75824246CFd0fCa8E7F7009fB8f720",
        "amount": "5000",
        "maxTimeoutSeconds": 60,
        "extra": {"name": "USDC", "version": "2"},
    }
    auth = build_authorization(account.address, req)
    sig = sign_eip3009(account, req, auth)
    assert sig.startswith("0x")
    # 0x + 130 hex chars = 65 bytes (r, s, v).
    assert len(sig) == 132


def test_build_x_payment_header_round_trip():
    req = {"payTo": "0x0", "amount": "5000", "maxTimeoutSeconds": 60, "description": "x"}
    auth = {"from": "0xa", "to": "0xb", "value": "5000", "validAfter": "0", "validBefore": "1", "nonce": "0x0"}
    header = build_x_payment_header("https://api.test/v1/x", req, auth, "0xdeadbeef")
    decoded = json.loads(base64.b64decode(header))
    assert decoded["x402Version"] == 2
    assert decoded["resource"]["url"] == "https://api.test/v1/x"
    assert decoded["payload"]["signature"] == "0xdeadbeef"


def test_decode_settlement_header_returns_none_for_empty():
    assert decode_settlement_header(None) is None
    assert decode_settlement_header("") is None


def test_decode_settlement_header_parses_valid_b64():
    payload = {"success": True, "txHash": "0xabc", "network": "eip155:84532"}
    encoded = base64.b64encode(json.dumps(payload).encode()).decode()
    assert decode_settlement_header(encoded) == payload


def test_summary_parses_camel_case_api_response():
    raw = {
        "rcptNo": "20260424900874",
        "summaryEn": "Global SM trading suspension.",
        "importanceScore": 7,
        "eventType": "SINGLE_STOCK_TRADING_SUSPENSION",
        "sectorTags": ["Communication Services"],
        "tickerTags": ["001680.KS"],
        "actionableFor": ["QUANT"],
        "generatedAt": "2026-04-24T09:15:00Z",
    }
    summary = Summary.model_validate(raw)
    assert summary.rcpt_no == "20260424900874"
    assert summary.importance_score == 7
    assert summary.ticker_tags == ["001680.KS"]


def test_summary_rejects_out_of_range_importance():
    raw = {
        "rcptNo": "x",
        "summaryEn": "",
        "importanceScore": 11,
        "eventType": "",
        "sectorTags": [],
        "tickerTags": [],
        "actionableFor": [],
        "generatedAt": "2026-04-24T09:15:00Z",
    }
    with pytest.raises(Exception):
        Summary.model_validate(raw)


def test_company_parses_camel_case_api_response():
    raw = {
        "ticker": "005930",
        "corpCode": "00126380",
        "nameKr": "삼성전자",
        "nameEn": "Samsung Electronics Co., Ltd.",
        "market": "KOSPI",
    }
    company = Company.model_validate(raw)
    assert company.ticker == "005930"
    assert company.corp_code == "00126380"
    assert company.name_en == "Samsung Electronics Co., Ltd."
    assert company.market == "KOSPI"


def test_company_omits_optional_name_en():
    raw = {
        "ticker": "012345",
        "corpCode": "00012345",
        "nameKr": "한국전력",
        "market": "KOSPI",
    }
    company = Company.model_validate(raw)
    assert company.name_en is None


def test_recent_filing_parses_camel_case_api_response():
    raw = {
        "rcptNo": "20260429000123",
        "ticker": "005930",
        "corpName": "Samsung Electronics",
        "reportNm": "유상증자결정",
        "rceptDt": "2026-04-29",
    }
    filing = RecentFiling.model_validate(raw)
    assert filing.rcpt_no == "20260429000123"
    assert filing.ticker == "005930"
    assert filing.report_nm == "유상증자결정"
    assert str(filing.rcept_dt) == "2026-04-29"


def test_settlement_proof_parses():
    proof = SettlementProof.model_validate(
        {
            "success": True,
            "errorReason": None,
            "transaction": "0xabc",
            "network": "eip155:84532",
            "payer": "0xdef",
        }
    )
    assert proof.success is True
    assert proof.tx_hash == "0xabc"
    assert proof.error_reason is None


def test_api_error_carries_status_and_body():
    err = ApiError(404, {"detail": "not found"})
    assert err.status_code == 404
    assert err.body == {"detail": "not found"}


def test_payment_error_message_includes_reason():
    err = PaymentError("bad_sig", {"why": "nope"})
    assert "bad_sig" in str(err)
