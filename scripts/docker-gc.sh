#!/usr/bin/env bash
# Reclaim Docker build cache and cap systemd journal size.
#
# Round-19 ops: a day of repeated `docker compose build` deploys grew
# the build cache to 23.4 GB and took the root filesystem to 83%. A
# full disk stops Postgres writes, so this runs on a schedule rather
# than waiting for someone to notice.
#
# Safe by construction: `builder prune` only removes build-layer cache,
# never running containers, tagged images, or volumes. The journal
# vacuum keeps the most recent 500 MB.
set -euo pipefail

BEFORE=$(df --output=pcent / | tail -1 | tr -dc '0-9')

docker builder prune -af --filter 'until=24h' >/dev/null 2>&1 || true
# Dangling (untagged) images left behind by rebuilds of the same tag.
docker image prune -f >/dev/null 2>&1 || true
journalctl --vacuum-size=500M >/dev/null 2>&1 || true

AFTER=$(df --output=pcent / | tail -1 | tr -dc '0-9')
echo "$(date -Is) docker-gc: disk ${BEFORE}% -> ${AFTER}%"

# Loud signal while there is still room to act, rather than at 100%.
if [ "$AFTER" -ge 80 ]; then
  echo "$(date -Is) docker-gc: WARNING disk still at ${AFTER}% after cleanup" >&2
fi
