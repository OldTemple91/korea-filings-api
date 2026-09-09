#!/usr/bin/env bash
# Keep the service inside Coinbase Bazaar's 30-day activity window by
# settling one paid call per paid endpoint from the payer wallet. The
# Bazaar indexes and expires resources individually, so both
# /v1/disclosures/summary (cached rcptNo → no LLM spend) and
# /v1/disclosures/by-ticker (limit=1) are settled: 0.01 USDC per run.
# Cron: 0 3 1,16 * *
#
# Expects <repo>/testclient/.env.testclient on this host with
# PAYER_PRIVATE_KEY (dedicated low-balance wallet), API_BASE_URL and
# TARGET_RCPT_NO. See docs/RUNBOOK.md scenario 14.
set -uo pipefail

REPO="${REPO:-/root/korea-filings-api}"
RCPT="${1:-${TARGET_RCPT_NO:-20260424900874}}"
BYTICKER="${BYTICKER_PATH:-/v1/disclosures/by-ticker?ticker=005930&limit=1}"
STAMP="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
cd "$REPO" || exit 2

if [ ! -f testclient/.env.testclient ]; then
  echo "$STAMP bazaar-heartbeat: FAIL missing testclient/.env.testclient" >&2
  exit 2
fi

run_payer() {
  local target="$1"
  if command -v uv >/dev/null 2>&1; then
    uv run --with-requirements testclient/requirements.txt testclient/payer.py "$target"
  else
    VENV="${VENV:-/root/.bazaar-heartbeat-venv}"
    if [ ! -x "$VENV/bin/python" ]; then
      python3 -m venv "$VENV"
      "$VENV/bin/pip" install -q -r testclient/requirements.txt
    fi
    "$VENV/bin/python" testclient/payer.py "$target"
  fi
}

# settle_one <label> <payer.py argument>; prints one log line, returns 0/1
settle_one() {
  local label="$1" target="$2" out tx bazaar
  out="$(run_payer "$target" 2>&1)" || true
  if grep -q '\[SETTLED\]' <<<"$out" && grep -q '"success": true' <<<"$out"; then
    tx="$(grep -oE '"transaction": "0x[0-9a-fA-F]+"' <<<"$out" | head -1 | grep -oE '0x[0-9a-fA-F]+')"
    echo "$STAMP bazaar-heartbeat: OK $label tx=${tx:-?}"
    return 0
  fi
  echo "$STAMP bazaar-heartbeat: FAIL $label" >&2
  echo "$out" | tail -15 >&2
  return 1
}

rc=0
settle_one "summary rcptNo=$RCPT" "$RCPT" || rc=1
settle_one "by-ticker $BYTICKER" "$BYTICKER" || rc=1
exit $rc
