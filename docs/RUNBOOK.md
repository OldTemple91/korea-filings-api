# Operational Runbook

What to do when something is wrong.

This file is the operator's playbook for production incidents and routine maintenance. Every scenario lists exact commands. The maintainer is the only operator today; this file exists so a future second operator (or the maintainer at 03:00 KST) can act without reading source code.

`<PROD_VM>` placeholder = the production Linux VPS hostname/IP.
`<REMOTE_REPO_DIR>` placeholder = where this repo is rsynced on the VM (e.g. `/root/korea-filings-api`).

---

## Quick reference

| Symptom | Section |
|---|---|
| API returns 5xx for users | [API down](#1-api-down--containers-restarting) |
| Cloudflare Tunnel red | [Tunnel down](#2-cloudflare-tunnel-disconnected) |
| Postgres connection errors | [Postgres outage](#3-postgres-outage) |
| Redis connection errors | [Redis outage](#4-redis-outage) |
| Settle failures spiking | [Facilitator outage](#5-coinbase-cdp-facilitator-outage) |
| Gemini quota errors | [Gemini quota exhausted](#6-gemini-quota-exhausted) |
| Container won't start (Flyway) | [Bad migration](#7-flyway-bad-migration--container-wont-boot) |
| Disk full warning | [Disk full](#8-disk-full--90) |
| Container OOM-killed | [JVM OOM](#9-jvm-oom-loop) |
| HN traffic spike | [Traffic spike](#10-hn-or-traffic-spike) |
| Suspected double charge | [Double charge](#11-suspected-double-charge--reconciliation) |
| DART blocks our IP | [DART block](#12-dart-api-blocks-our-key) |
| **Restore from backup** | [Restore Postgres](#restore-postgres-from-backup) |
| **Rotate a secret** | [Secret rotation](#secret-rotation) |
| **Rebuild the VM** | [VM rebuild](#vm-rebuild-from-scratch) |

---

## Verifying a payment landed

Where the operator confirms an external paid call:

```bash
# 1. Postgres — single source of truth.
docker exec dartintel-postgres psql -U dartintel -d dartintel -c \
  "SELECT id, payer_address, amount_usdc, network, facilitator_tx_id, settled_at
   FROM payment_log ORDER BY id DESC LIMIT 10;"

# 2. On-chain (Base mainnet) — cross-check the tx hash.
#    https://basescan.org/tx/<facilitator_tx_id>

# 3. Slack/Discord webhook — if X402_NOTIFY_WEBHOOK_URL is set the
#    operator gets a per-payment notification automatically.
#    See `Secret rotation > X402_NOTIFY_WEBHOOK_URL` to set/rotate.
```

---

## Incident playbook

### 1. API down / containers restarting

**Symptom**: external HTTP returns 5xx; `/actuator/health/liveness` is red; docker keeps restarting `dartintel-app`.

```bash
ssh root@<PROD_VM> 'docker compose --profile prod ps'
ssh root@<PROD_VM> 'docker logs --tail 200 dartintel-app 2>&1 | tail -200'
ssh root@<PROD_VM> 'curl -s http://localhost:8080/actuator/health | python3 -m json.tool'
```

Common causes from the logs:
- `IllegalStateException: x402.token-name must be ...` → bad `.env`. See [secret rotation](#secret-rotation).
- `Failed to instantiate CdpJwtSigner ... incorrect ending byte` → corrupted `X402_CDP_PRIVATE_KEY`. Re-rsync the key.
- `Validation of migration ... failed` → see [Flyway bad migration](#7-flyway-bad-migration--container-wont-boot).
- Postgres / Redis healthcheck failing → see those sections.

If logs show no obvious cause and the container was healthy then suddenly OOM-killed, see [JVM OOM](#9-jvm-oom-loop).

---

### 2. Cloudflare Tunnel disconnected

**Symptom**: `koreafilings.com` and `api.koreafilings.com` return Cloudflare 502; the app itself is healthy on `localhost:8080` (ssh in and curl).

```bash
ssh root@<PROD_VM> 'docker logs --tail 50 dartintel-tunnel 2>&1'
ssh root@<PROD_VM> 'docker compose --profile prod restart cloudflared'
```

If restart doesn't recover, the tunnel token may be revoked / corrupted:
1. Cloudflare dashboard → Zero Trust → Networks → Tunnels.
2. Confirm the tunnel shows "Healthy" connectors.
3. If the connector is missing, delete + recreate; refresh `CLOUDFLARE_TUNNEL_TOKEN` in `.env`; bounce the cloudflared container.

---

### 3. Postgres outage

**Symptom**: paid endpoints return `503 service_unavailable` with `Retry-After: 10` (handled by `ApiExceptionHandler`); free endpoints same.

```bash
ssh root@<PROD_VM> 'docker compose --profile prod ps postgres'
ssh root@<PROD_VM> 'docker logs --tail 100 dartintel-postgres 2>&1'
ssh root@<PROD_VM> 'docker exec dartintel-postgres pg_isready -U dartintel'
```

If Postgres is just slow (high CPU / locks):
```bash
docker exec -it dartintel-postgres psql -U dartintel -d dartintel \
  -c "SELECT pid, now()-query_start AS duration, query
      FROM pg_stat_activity
      WHERE state='active' ORDER BY duration DESC LIMIT 10;"
```

Restart only as a last resort (loses in-flight transactions; Flyway state survives):
```bash
docker compose --profile prod restart postgres
```

If Postgres is corrupt and won't start, see [Restore Postgres](#restore-postgres-from-backup).

---

### 4. Redis outage

**Symptom**: paid endpoints 503; `SummaryJobConsumer` logs "Consumer loop error" repeatedly.

```bash
ssh root@<PROD_VM> 'docker logs --tail 50 dartintel-redis 2>&1'
ssh root@<PROD_VM> 'docker exec dartintel-redis redis-cli -a "$REDIS_PASSWORD" ping'
```

Restart:
```bash
docker compose --profile prod restart redis
```

After restart:
- `summary_job_queue` may have lost in-flight items. `SummaryRetryScheduler` rebuilds the queue from Postgres (`findRcptNosMissingSummary`) within 5 minutes. No data is lost — only delayed.
- `payment_sig:*` replay keys lost their TTL. Replay protection falls back to the on-chain EIP-3009 nonce check; the facilitator rejects replayed nonces.
- `dart_last_rcept_dt` watermark loss → next poll re-fetches the last `initial-cursor-days-back` of filings. `existsByRcptNo` deduplicates so no duplicate rows; just a re-enqueue burst.

The Redis volume uses `appendonly yes` with `appendfsync everysec` (default) — up to 1 second of writes can be lost on a hard crash. Acceptable for this workload.

---

### 5. Coinbase CDP facilitator outage

**Symptom**: paid endpoints return 402 with `PAYMENT-RESPONSE.success=false errorReason=settle_unavailable`; circuit breaker `facilitator` opens.

```bash
ssh root@<PROD_VM> 'curl -s http://localhost:8080/actuator/circuitbreakers \
  | python3 -m json.tool'
```

CDP is opaque to us — check status.coinbase.com. There's no fallback facilitator on mainnet (the public x402.org one is testnet-only).

Action:
1. Verify CDP is the cause and not our keys / network.
2. Wait for CDP recovery (typically minutes); the circuit breaker will automatically half-open and recover.
3. If sustained, post a status update on `koreafilings.com` if the landing page has a status section, or pin a tweet.

The fail-closed design means no data is leaked; agents see 402 + spec-compliant error and can retry once CDP recovers.

---

### 6. Gemini quota exhausted

**Symptom**: `summary_job_queue` depth grows; `RequestNotPermitted` exceptions in `SummaryJobConsumer` logs.

```bash
# Queue depth
docker exec dartintel-redis redis-cli -a "$REDIS_PASSWORD" llen summary_job_queue

# Rate limiter state
curl -s http://localhost:8080/actuator/ratelimiters | python3 -m json.tool
```

If sustained: bump quota in Google AI Studio (the Quota #4 we set to 60 RPM was for that exact reason). Without a quota change, the queue catches up at 10 RPM (free tier) once the burst subsides — 100 backlog filings drain in 10 minutes.

---

### 7. Flyway bad migration / container won't boot

**Symptom**: `dartintel-app` keeps restarting; logs show `Validation of migration V<N>__<name>.sql failed: Migration checksum mismatch` or similar.

```bash
ssh root@<PROD_VM> 'docker logs --tail 200 dartintel-app 2>&1 | grep -i flyway | tail -20'
```

If the migration ran but the SQL file has been edited (checksum mismatch):
```bash
docker exec -it dartintel-postgres psql -U dartintel -d dartintel \
  -c "SELECT version, description, checksum FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

Two recovery paths:
- **Repair (if you know the SQL applied is correct)**: `docker exec dartintel-postgres psql -U dartintel -d dartintel -c "UPDATE flyway_schema_history SET checksum=<new> WHERE version='<N>';"` then restart the app.
- **Roll forward**: write a `V<N+1>__fix.sql` that compensates for the bad migration. Never edit a migration that has already shipped.

If a migration legitimately broke the schema (e.g. a column rename that lost data), restore from the most recent backup before the bad migration ran. See [Restore Postgres](#restore-postgres-from-backup).

---

### 8. Disk full > 90%

**Symptom**: `df -h` shows VPS storage > 90%; Postgres or Redis writes start failing.

```bash
ssh root@<PROD_VM> 'df -h'
ssh root@<PROD_VM> 'du -sh /var/lib/docker/containers/*/*-json.log | sort -rh | head -5'
ssh root@<PROD_VM> 'du -sh /var/lib/docker/volumes/*'
```

Quick wins:
- Container logs are capped at 50MB × 5 files per service via `logging:` block in `docker-compose.yml`. If a service is over (e.g. log rotation didn't fire), `docker compose restart <service>` triggers rotation.
- `docker system prune -f` reclaims build cache and stopped containers.
- Old Postgres backups (`/var/backups/dartintel/*.sql.gz.age`) — `pg-backup.sh` retains 7 days by default.

---

### 9. JVM OOM loop

**Symptom**: `dartintel-app` repeatedly OOM-killed; `docker logs` shows `OutOfMemoryError` near the end of each life cycle.

```bash
ssh root@<PROD_VM> 'docker logs --tail 500 dartintel-app 2>&1 | grep -iE "outofmemory|killed" | tail'
ssh root@<PROD_VM> 'docker stats --no-stream dartintel-app'
```

Recovery:
1. Bump VPS RAM if at the edge.
2. The most likely culprit is the `corpCode.xml` parse in `CompanySyncScheduler` — disable temporarily by adding `COMPANY_SYNC_ENABLED: "false"` to the compose env and bouncing the app.
3. Inspect the heap dump if `-XX:+HeapDumpOnOutOfMemoryError` is set (it isn't by default; consider adding for next deploy).

If the leak recurs after corp sync is disabled, it's likely a cache or job-queue grow path. File an incident note and roll back to the previous JAR.

---

### 10. HN or traffic spike

**Symptom**: `tomcat_threads_busy_threads` (Prometheus) climbs above 150; latency p95 spikes; users report slow responses.

The Tomcat thread pool is 200 by default; Hikari pool is 20. The system degrades gracefully (queues, then 503s) rather than crashing.

Mitigation in priority order:
1. **Cloudflare rate limiting**: dashboard → Security → WAF → Rate limiting rules → cap to 60 req/min/IP. Takes effect in seconds, no code change.
2. **Cloudflare cache rule** on `/v1/disclosures/recent`: the response already advertises `Cache-Control: public, max-age=30` (in code as of v0.5.x). Add a Cloudflare cache rule on this path so the edge absorbs polling traffic.
3. Vertical scale: bump VPS to next tier; `docker compose --profile prod restart` to re-up. ~30s downtime.
4. Async facilitator calls (code change, see capacity audit) — the ~1.5s per-paid-call thread hold is the binding constraint at sustained burst.

---

### 11. Suspected double charge — reconciliation

Customer (or BaseScan watcher) reports a wallet was charged twice for the same call. The replay protection plus on-chain EIP-3009 nonce should prevent this — but if it slipped through:

```bash
# All payments from the affected payer.
docker exec dartintel-postgres psql -U dartintel -d dartintel -c \
  "SELECT id, endpoint, amount_usdc, facilitator_tx_id, signature_hash, settled_at
   FROM payment_log
   WHERE payer_address = '<address>'
   ORDER BY settled_at DESC LIMIT 50;"
```

Cross-check against on-chain at `https://basescan.org/address/0x8467Be164C75824246CFd0fCa8E7F7009fB8f720#tokentxns` (merchant wallet). Look for two `transferWithAuthorization` calls from the same payer in close time proximity.

Outcomes:
- Same `signature_hash` but two `payment_log` rows → replay-guard bug. UNIQUE constraint should have prevented the second insert; if it didn't, V4 migration is broken.
- Different `signature_hash` but same nonce field within the signed authorisations → facilitator bug. Escalate to Coinbase CDP support.
- Different nonces, two genuine payments → user error, document and refund manually if appropriate.

Manual refund: send USDC from the merchant wallet directly via Coinbase Wallet / Base.

---

### 12. DART API blocks our key

**Symptom**: `DartPollingScheduler` logs `DART API error status=011` (rate-limited) or `403`; ingestion stops.

DART rate-limits per API key. Recovery:
1. Register a second key at `opendart.fss.or.kr` (free, ~5 minutes).
2. Update `DART_API_KEY` in `.env`.
3. Bounce app: `docker compose --profile prod up -d --force-recreate app`.

The block is per-key, not per-IP, so a key swap is sufficient.

---

## Restore Postgres from backup

Backup format: `${DB_NAME}_<UTC>.sql.gz.age` produced by `scripts/pg-backup.sh`.

You need:
- The age **private** key matching the recipient that produced the backup. **This private key MUST NOT live on the production VM** — keep it in a password manager / hardware key.
- The encrypted dump file.

```bash
# 1. Stop the app so Flyway doesn't race the restore.
ssh root@<PROD_VM> 'docker compose --profile prod stop app'

# 2. Decrypt + restore on the VM.
scp ~/.config/age/keys.txt root@<PROD_VM>:/tmp/age-key  # one-shot; delete after
ssh root@<PROD_VM> '
    age --decrypt --identity /tmp/age-key /var/backups/dartintel/<dump>.sql.gz.age \
      | gunzip \
      | docker exec -i dartintel-postgres psql -U dartintel -d dartintel
    rm /tmp/age-key
'

# 3. Restart the app — Flyway validates against the restored schema.
ssh root@<PROD_VM> 'docker compose --profile prod up -d app'

# 4. Sanity-check the restore.
ssh root@<PROD_VM> 'docker exec dartintel-postgres psql -U dartintel -d dartintel \
  -c "SELECT count(*) FROM payment_log; SELECT count(*) FROM disclosure_summary;"'
```

**RPO** (target): ≤ 6 hours. Backup cron runs every 6 hours.
**RTO** (target): ≤ 1 hour from operator notification to restored service.

Test the restore at least once per quarter against a staging VM. Untested backups are not real backups.

---

## Secret rotation

| Secret | Rotation procedure |
|---|---|
| `POSTGRES_PASSWORD` | (1) `docker exec dartintel-postgres psql -U dartintel -c "ALTER USER dartintel WITH PASSWORD '<new>';"`. (2) Update `.env` on VM. (3) `docker compose --profile prod up -d --force-recreate app`. ~30s of paid-API downtime during step 3. |
| `REDIS_PASSWORD` | (1) Update `.env` on VM. (2) `docker compose --profile prod up -d --force-recreate redis app`. ~60s of downtime; the `summary_job_queue` survives via AOF. |
| `DART_API_KEY` | (1) Get new key from opendart.fss.or.kr. (2) Update `.env`. (3) `docker compose --profile prod up -d --force-recreate app`. |
| `GEMINI_API_KEY` | (1) Generate new key in Google AI Studio. (2) Update `.env`. (3) Same restart as above. |
| `X402_CDP_KEY_ID` + `X402_CDP_PRIVATE_KEY` | **Compromise response**: (1) Revoke the old key in the [CDP portal](https://portal.cdp.coinbase.com) — takes effect in seconds. (2) Create a new Ed25519 key with x402 facilitator scopes only. (3) Update both env vars in `.env`. (4) Restart app. (5) Audit CDP API logs for any settlements attempted with the compromised key after the leak window. The CDP key only signs JWTs to authenticate to the facilitator — it does NOT control the recipient wallet (`X402_RECIPIENT_ADDRESS` is receive-only by us). |
| `X402_RECIPIENT_ADDRESS` | (1) Update `.env` on VM. (2) Restart app. **Caveat**: agents that cached the 402 challenge from the old wallet will continue signing EIP-3009 authorisations to the old `payTo` for the duration of their cached challenge. This is harmless to us (they can't pay the new wallet), but expect spec-compliant clients to retry once they hit a fresh 402 and see the new address. |
| `CLOUDFLARE_TUNNEL_TOKEN` | (1) Cloudflare dashboard → Zero Trust → Networks → Tunnels → revoke the connector. (2) Issue a new token. (3) Update `.env`. (4) `docker compose --profile prod up -d --force-recreate cloudflared`. The leaked token loses the ability to receive any new traffic the moment it's revoked — even if an attacker is running a connector with it. |
| `X402_NOTIFY_WEBHOOK_URL` | Slack/Discord webhook URL is sensitive (a leak lets an attacker spam the operator's channel). Revoke in the Slack/Discord admin UI; generate a new one; update `.env`; restart app. |
| `AGE_RECIPIENT` (backup encryption) | If the matching private key leaks, all past backups are readable by the holder. (1) Generate a new keypair locally with `age-keygen`. (2) Update the VM's cron with the new public key. (3) Re-encrypt past backups if you can; otherwise discard them and start fresh. |

---

## VM rebuild from scratch

Replace a dead VPS with a fresh one. Cloudflare Tunnel makes the network plumbing trivial — no DNS update needed, the tunnel reconnects to whichever connector is healthy.

```bash
# On the new VM (Ubuntu 22.04+):
curl -fsSL https://get.docker.com | sh
sudo apt install -y rsync age

# From maintainer workstation:
mkdir -p /root/korea-filings-api  # placeholder; do via ssh
rsync -az --delete \
  --exclude='.git' --exclude='build/' --exclude='.venv' \
  --exclude='*/.venv' --exclude='testclient/.env.testclient' \
  --exclude='.env*' \
  ./ root@<NEW_PROD_VM>:/root/korea-filings-api/

# Copy `.env` separately (off-band, one-shot):
scp .env root@<NEW_PROD_VM>:/root/korea-filings-api/.env
ssh root@<NEW_PROD_VM> 'chmod 600 /root/korea-filings-api/.env'

# Restore the Postgres dump (see "Restore Postgres" above).

# Bring the stack up.
ssh root@<NEW_PROD_VM> 'cd /root/korea-filings-api && \
  docker compose --profile prod up -d'

# Verify.
ssh root@<NEW_PROD_VM> 'curl -s localhost:8080/actuator/health/liveness'

# The Cloudflare Tunnel auto-reconnects when cloudflared starts.
# DNS doesn't need updating because the tunnel is the only ingress.
```

Total time from "VM dead" to "API back up" with a tested backup: ~30 minutes.

---

## Daily / weekly checks

| Cadence | Check | Command |
|---|---|---|
| Daily | Disk usage | `ssh root@<PROD_VM> 'df -h'` |
| Daily | Backup ran | `ssh root@<PROD_VM> 'ls -lt /var/backups/dartintel \| head -3'` |
| Daily | Off-site copy ran | (R2 / S3 dashboard or `rclone ls r2:dartintel-backups`) |
| Weekly | Container health | `ssh root@<PROD_VM> 'docker compose --profile prod ps'` |
| Weekly | Settlements landed | `psql ... payment_log latest 7 days` |
| Monthly | Test restore on staging | follow [Restore Postgres](#restore-postgres-from-backup) on a fresh VM |
| Quarterly | Rotate secrets | follow [Secret rotation](#secret-rotation) for at least one secret |

If any check fails, the operator enters incident mode for that scenario.
