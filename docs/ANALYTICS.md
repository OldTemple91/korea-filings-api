# Analytics & KPI Queries

What this is: a reusable SQL playbook for the `request_audit` table populated by `RequestAuditFilter` + `RequestAuditPersister`. The filter writes one row per non-GET request OR any 4xx/5xx response. Rows older than 90 days are pruned nightly at 04:00 UTC.

> **Payment-side observability lives elsewhere.** This doc covers HTTP traffic shape (who hits us, with what UA, with what `X-PAYMENT` flag). Settlement-side health (does every paid 200 produce a `payment_log` row?) is exposed as Prometheus signals on `/actuator/prometheus`:
>
> - `payment_log_reconciliation_gap_rows` — per-minute gauge of rows older than 5 min with `facilitator_tx_id IS NULL`. Target: 0 (per [SLO.md](SLO.md)).
> - `payment_log_reconciliation_failures_total` — counter incremented from the integrity-violation / DB-down branches of `X402SettlementAdvice`.
>
> A non-zero reading on either is a P0 ledger drift; [`docs/RUNBOOK.md`](RUNBOOK.md#12-payment_log_reconciliation_gap_rows--0-or-_failures_total-ticking) carries the response checklist.

Run on the production VM:

```bash
ssh root@<vm> 'docker exec -i dartintel-postgres psql -U dartintel -d dartintel'
```

Or copy/paste any of the queries below into a `psql` session.

## Why this exists

The `REQ_AUDIT` log lines roll every 50 MB × 5 generations (`docker-compose.yml`'s `x-logging`), so anything older than ~a week of busy traffic is gone. That's fine for live debugging, useless for "did the HN launch shift our discovery → first paid call funnel?" — which is exactly what early-stage operating decisions need.

The persisted rows give a 90-day window for:

- **Funnel analysis** — discovery hits → 402 challenges → signed retries → settled 200s
- **Cohort comparison** — week-over-week traffic mix, before/after a release
- **New-integration emergence** — a UA never seen before, or a known UA that started signing
- **Decision support** — answers "should we open POST endpoints?" / "is the TS SDK announcement working?" with numbers, not vibes

## Schema

```sql
\d request_audit
```

Columns: `ts`, `method`, `path`, `status`, `ip`, `user_agent`, `query_keys` (sorted CSV of param names, **never values**), `body_bytes`, `content_type`, `has_x_payment`, `has_payment_sig` (booleans — header values never persisted).

## Daily KPIs

### Q1 — total qualified requests per day

A "qualified" request is non-GET OR any 4xx/5xx response (matches the filter's emit predicate). Successful free GETs are intentionally not stored.

```sql
SELECT date_trunc('day', ts) AS day, count(*) AS qualified_requests
FROM   request_audit
WHERE  ts > now() - interval '30 days'
GROUP  BY 1
ORDER  BY 1 DESC;
```

### Q2 — request volume by user-agent category

The category bucketing here mirrors the manual analysis we ran on raw logs. Tweak as new agents emerge.

```sql
WITH categorised AS (
  SELECT date_trunc('day', ts) AS day,
         CASE
           WHEN user_agent LIKE '%axios%'              THEN 'axios'
           WHEN user_agent LIKE '%ClaudeBot%'          THEN 'claudebot'
           WHEN user_agent LIKE '%GPTBot%'             THEN 'gptbot'
           WHEN user_agent LIKE '%OAI-SearchBot%'      THEN 'oai-searchbot'
           WHEN user_agent LIKE '%Googlebot%'          THEN 'googlebot'
           WHEN user_agent LIKE '%facebookexternal%'   THEN 'facebook'
           WHEN user_agent LIKE '%x402audit%'          THEN 'x402audit'
           WHEN user_agent LIKE '%Open402%'            THEN 'open402-directory'
           WHEN user_agent LIKE '%flows-crawler%'      THEN 'flows-crawler'
           WHEN user_agent LIKE '%SmartFlow%'          THEN 'smartflow-uptime'
           WHEN user_agent LIKE '%Mozilla%iPhone%'     THEN 'mobile-bot-farm'
           WHEN user_agent = 'node'                    THEN 'node-default'
           WHEN user_agent LIKE 'curl%'                THEN 'curl'
           WHEN user_agent LIKE 'Go-http%'             THEN 'go-http'
           WHEN user_agent IS NULL                     THEN '(missing)'
           ELSE 'other'
         END AS ua_category,
         status
  FROM   request_audit
  WHERE  ts > now() - interval '30 days'
)
SELECT day, ua_category, count(*) AS requests
FROM   categorised
GROUP  BY 1, 2
ORDER  BY 1 DESC, 3 DESC;
```

### Q3 — discovery file probes (llms.txt, robots.txt, sitemap.xml, agent.json)

```sql
SELECT path, count(*) AS hits, count(distinct ip) AS unique_ips
FROM   request_audit
WHERE  path IN ('/llms.txt', '/robots.txt', '/sitemap.xml',
                '/.well-known/agent.json', '/.well-known/x402',
                '/favicon.ico', '/')
  AND  ts > now() - interval '30 days'
GROUP  BY 1
ORDER  BY 2 DESC;
```

If `llms.txt` / `agent.json` hit counts trend up week-over-week, the AI-agent indexing surface is working. If they go to zero, something broke (Cloudflare cache rule misconfiguration, file deleted, etc).

## Funnel (the headline KPI)

The cold-start agent flow is `discovery → free probe → 402 → signed retry → settled 200`. Each transition has a measurable conversion rate.

```sql
WITH window AS (
  SELECT * FROM request_audit WHERE ts > now() - interval '7 days'
),
events AS (
  SELECT 'step3_402_received'     AS event, count(*) AS n
    FROM window WHERE status = 402
  UNION ALL
  SELECT 'step4_signed_request'   AS event, count(*) AS n
    FROM window WHERE has_payment_sig OR has_x_payment
  UNION ALL
  SELECT 'step5_settled_200_paid' AS event, count(*) AS n
    FROM window
   WHERE status = 200
     AND (has_payment_sig OR has_x_payment)
)
SELECT * FROM events ORDER BY event;
```

The biggest leak is usually **step 3 → step 4** (got 402 challenge, never signed). That's where v1's rcpt_no mistake hid for a while — agents knew the endpoint shape but couldn't construct the payment payload. Same diagnostic still applies.

## Cohort comparison (post-release retrospective)

Compare two consecutive 7-day windows so a release / launch shows up as a delta:

```sql
WITH bucket AS (
  SELECT CASE WHEN ts > now() - interval '7 days'  THEN 'this_week'
              WHEN ts > now() - interval '14 days' THEN 'last_week'
         END AS week,
         status, has_payment_sig, has_x_payment
  FROM   request_audit
  WHERE  ts > now() - interval '14 days'
)
SELECT week,
       count(*)                                     AS qualified,
       count(*) FILTER (WHERE status = 402)         AS got_402,
       count(*) FILTER (WHERE has_payment_sig
                          OR  has_x_payment)        AS signed,
       count(*) FILTER (WHERE status = 200
                          AND (has_payment_sig
                            OR has_x_payment))      AS settled_200
FROM   bucket
WHERE  week IS NOT NULL
GROUP  BY 1
ORDER  BY 1;
```

Useful right after each of:
- TypeScript SDK announcement
- HN Show HN
- New x402scan / Glama / Smithery directory listing
- README rewrite that changes positioning

## Detecting a new integration emerge

Run weekly. Lists user-agents seen for the first time in the past 7 days that signed at least one request — i.e., "a new payment-capable client just discovered us":

```sql
WITH first_seen AS (
  SELECT user_agent, min(ts) AS first_ts
  FROM   request_audit
  WHERE  user_agent IS NOT NULL
  GROUP  BY user_agent
)
SELECT  fs.user_agent,
        fs.first_ts,
        sum(CASE WHEN ra.has_payment_sig OR ra.has_x_payment
                 THEN 1 ELSE 0 END)        AS signed_calls,
        count(*)                           AS total_calls
FROM    first_seen fs
JOIN    request_audit ra ON ra.user_agent = fs.user_agent
WHERE   fs.first_ts > now() - interval '7 days'
GROUP   BY fs.user_agent, fs.first_ts
HAVING  sum(CASE WHEN ra.has_payment_sig OR ra.has_x_payment
                 THEN 1 ELSE 0 END) > 0
ORDER   BY 2 DESC;
```

A non-empty result deserves attention. The agent has been operating for less than a week and is signing payments — exactly the early-customer signal.

## "Stuck loop" diagnosis

A common pattern observed early in the audit data: a single client retrying the same broken request shape (e.g. POST against a GET-only endpoint, or empty body where query parameters are required) hundreds of times without learning from the 405 / 400 response. This query surfaces such loops so an operator can decide whether to ignore (cheap), block at Cloudflare, or reach out to the implementer:

```sql
SELECT ip,
       user_agent,
       count(*)                             AS attempts,
       count(distinct (method, path, status)) AS distinct_signatures,
       min(ts)                              AS first_seen,
       max(ts)                              AS last_seen
FROM   request_audit
WHERE  ts > now() - interval '7 days'
GROUP  BY ip, user_agent
HAVING count(*) > 100
   AND count(distinct (method, path, status)) <= 2
ORDER  BY 3 DESC;
```

Any caller with > 100 attempts in 7 days but ≤ 2 distinct (method, path, status) tuples is repeating the same broken request. They're either:
- Stuck (no learning) — ignore
- Or DOS-ing — block at Cloudflare (see RUNBOOK.md)

## "Are crawlers replacing real agents?" guardrail

Discovery crawlers (ClaudeBot, GPTBot, Googlebot, Open402DirectoryCrawler) are noise for revenue-tracking purposes. To exclude them when computing real-agent funnels:

```sql
SELECT count(*) AS real_agent_qualified_requests
FROM   request_audit
WHERE  ts > now() - interval '7 days'
  AND  user_agent NOT SIMILAR TO
       '%(ClaudeBot|GPTBot|OAI-SearchBot|Googlebot|facebookexternal|Open402|x402audit|flows-crawler|SmartFlow)%';
```

This number divided by Q1's total tells you how much of the audit log is "real prospects vs. ecosystem noise". Useful when reporting traffic numbers honestly.

## Operational housekeeping

### How big is the table?

```sql
SELECT pg_size_pretty(pg_total_relation_size('request_audit')) AS size,
       count(*)                                                AS rows,
       min(ts)                                                 AS oldest,
       max(ts)                                                 AS newest
FROM   request_audit;
```

If size grows beyond a few hundred MB, lower retention from 90 days to 30 (set `RequestAuditPersister.RETENTION_DAYS`) and re-deploy.

### Verify nightly prune is running

```sql
SELECT date_trunc('day', ts) AS day, count(*) AS rows
FROM   request_audit
GROUP  BY 1
ORDER  BY 1;
```

The bottom row's `day` should never be older than 90 days. If it is, the prune scheduler is broken.
