#!/usr/bin/env bash
# Keep the service inside Coinbase Bazaar's 30-day activity window by
# settling one cached paid call from the payer wallet. Idempotent and
# cheap (0.005 USDC, cache hit → no LLM spend). Cron: 0 3 1,16 * *
#
# Expects <repo>/testclient/.env.testclient on this host with
# PAYER_PRIVATE_KEY (dedicated low-balance wallet), API_BASE_URL and
# TARGET_RCPT_NO. See docs/RUNBOOK.md scenario 14.
set -euo pipefail

REPO="${REPO:-/root/korea-filings-api}"
RCPT="${1:-${TARGET_RCPT_NO:-20260424900874}}"
STAMP="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
cd "$REPO"

if [ ! -f testclient/.env.testclient ]; then
  echo "$STAMP bazaar-heartbeat: FAIL missing testclient/.env.testclient" >&2
  exit 2
fi

run_payer() {
  if command -v uv >/dev/null 2>&1; then
    uv run --with-requirements testclient/requirements.txt testclient/payer.py "$RCPT"
  else
    VENV="${VENV:-/root/.bazaar-heartbeat-venv}"
    if [ ! -x "$VENV/bin/python" ]; then
      python3 -m venv "$VENV"
      "$VENV/bin/pip" install -q -r testclient/requirements.txt
    fi
    "$VENV/bin/python" testclient/payer.py "$RCPT"
  fi
}

OUT="$(run_payer 2>&1)" || true
if grep -q '\[SETTLED\]' <<<"$OUT" && grep -q '"success": true' <<<"$OUT"; then
  TX="$(grep -oE '"transaction": "0x[0-9a-fA-F]+"' <<<"$OUT" | head -1 | grep -oE '0x[0-9a-fA-F]+')"
  echo "$STAMP bazaar-heartbeat: OK rcptNo=$RCPT tx=${TX:-?}"
  exit 0
fi
echo "$STAMP bazaar-heartbeat: FAIL rcptNo=$RCPT" >&2
echo "$OUT" | tail -20 >&2
exit 1
