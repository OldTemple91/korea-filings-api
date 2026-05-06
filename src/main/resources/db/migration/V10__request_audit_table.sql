-- request_audit
--
-- Long-term storage of the same key=value lines that
-- RequestAuditFilter writes to stdout. The log file rolls every 50 MB
-- (docker-compose.yml `x-logging`) and keeps at most 5 generations,
-- so anything older than ~a week of busy traffic is gone — fine for
-- live debugging, useless for "discovery → first paid call" funnel
-- analysis across releases.
--
-- This table is the persistent backbone behind docs/ANALYTICS.md
-- queries: per-day requests by UA category, discovery file hits,
-- 402 → signed retry conversion, agent integration emergence.
--
-- NEVER store any header value (X-PAYMENT, PAYMENT-SIGNATURE) — those
-- carry signed nonces that should not enter long-term storage. Only
-- the boolean presence flag goes here.
--
-- Pruning is the application's responsibility (see
-- RequestAuditPersister.pruneOldRows). 90-day retention is plenty for
-- monthly cohort comparisons and stays comfortably under the disk
-- ceiling at any plausible v1.x traffic volume.

CREATE TABLE request_audit (
    id              BIGSERIAL PRIMARY KEY,
    -- ts is the application's view of the audit moment, NOT clock_timestamp(),
    -- so a batched INSERT keeps every row's ts pinned to when the request
    -- actually happened rather than the flush moment.
    ts              TIMESTAMP WITH TIME ZONE NOT NULL,
    method          VARCHAR(8)               NOT NULL,
    path            VARCHAR(256)             NOT NULL,
    status          INT                      NOT NULL,
    ip              VARCHAR(64),
    user_agent      VARCHAR(256),
    -- Sorted, comma-separated list of query parameter NAMES.
    -- Values never recorded — values can carry rcptNo / ticker which
    -- are public, but storing them adds zero analytic value over the
    -- key set and increases dump size for no reason.
    query_keys      VARCHAR(512),
    body_bytes      BIGINT,
    content_type    VARCHAR(128),
    has_x_payment   BOOLEAN                  NOT NULL DEFAULT FALSE,
    has_payment_sig BOOLEAN                  NOT NULL DEFAULT FALSE
);

-- Most analytics queries scope to a recent time window, so a
-- DESC index on ts handles "last N days" scans efficiently.
CREATE INDEX idx_request_audit_ts ON request_audit (ts DESC);

-- A combined (ts, status) index lets the funnel queries
-- (count(*) where status=402, status=200, etc.) avoid a full scan
-- of the past N days for one status filter.
CREATE INDEX idx_request_audit_ts_status ON request_audit (ts DESC, status);
