#!/usr/bin/env bash
# pg-backup.sh — encrypted Postgres dump for the Korea Filings VM.
#
# Runs `pg_dump` against the dartintel-postgres container, gzips the
# output, encrypts with `age` (modern, simple, no GPG keyring), and
# writes to ${BACKUP_DIR}. Old encrypted files past ${RETAIN_DAYS} are
# deleted on each run.
#
# Off-site copy (S3 / R2 / B2) is the operator's responsibility — see
# the OFFSITE block at the bottom for an rclone example. Without an
# off-site copy this script protects against operator error / data
# corruption but NOT against full VM loss.
#
# REQUIREMENTS
#   - docker (or docker compose) on the host
#   - age (https://github.com/FiloSottile/age) — `apt install age`
#   - $AGE_RECIPIENT env var set to a public age key
#       (generate locally: `age-keygen` and keep the matching private
#        key OFF the VM — backups are useless if the key lives next to
#        the data they protect)
#
# DEPLOYMENT
#   1. Copy this file to /root/scripts/pg-backup.sh on the VM.
#   2. chmod +x /root/scripts/pg-backup.sh
#   3. Add a cron entry:
#        0 */6 * * * /root/scripts/pg-backup.sh > /var/log/pg-backup.log 2>&1
#      (every 6 hours; tighten to hourly once external paid traffic is observable.)
#
# RESTORE — see docs/RUNBOOK.md "Restore Postgres" section.

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/dartintel}"
RETAIN_DAYS="${RETAIN_DAYS:-7}"
CONTAINER="${CONTAINER:-dartintel-postgres}"
DB_USER="${DB_USER:-dartintel}"
DB_NAME="${DB_NAME:-dartintel}"

if [[ -z "${AGE_RECIPIENT:-}" ]]; then
    echo "FATAL: AGE_RECIPIENT not set. Generate a key with `age-keygen` and" >&2
    echo "       export AGE_RECIPIENT='age1...'" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="$BACKUP_DIR/${DB_NAME}_${TIMESTAMP}.sql.gz.age"

echo "[$(date -u -Iseconds)] starting pg_dump → $DUMP"
docker exec -i "$CONTAINER" pg_dump --clean --if-exists -U "$DB_USER" "$DB_NAME" \
    | gzip --best \
    | age --recipient "$AGE_RECIPIENT" --output "$DUMP"

# Sanity check: file exists and is non-empty.
if [[ ! -s "$DUMP" ]]; then
    echo "FATAL: backup file is empty: $DUMP" >&2
    exit 1
fi
SIZE=$(du -h "$DUMP" | cut -f1)
echo "[$(date -u -Iseconds)] dump complete: $SIZE"

# Prune old backups.
find "$BACKUP_DIR" -type f -name "*.sql.gz.age" -mtime +"$RETAIN_DAYS" -delete \
    && echo "[$(date -u -Iseconds)] pruned files older than ${RETAIN_DAYS} days"

# OFFSITE — uncomment + configure rclone remote `r2:dartintel-backups`.
# The off-site copy is mandatory before this script counts as real
# disaster recovery; without it, a host loss takes the backup with it.
#
# rclone copy "$DUMP" r2:dartintel-backups/ --progress
# echo "[$(date -u -Iseconds)] offsite copy complete"

echo "[$(date -u -Iseconds)] backup OK"
