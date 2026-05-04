-- V8 — close 5 audit findings from the round-4/5 cross-validation pass.
--
-- 1. /v1/disclosures/recent uses `findRecentSince` with WHERE created_at
--    >= :since AND ticker IS NOT NULL ORDER BY created_at DESC. The
--    existing indices cover (rcept_dt, rcpt_no) and (ticker, rcept_dt)
--    but nothing covers `created_at`. The free recent-feed therefore
--    sequential-scans the disclosure table — fine at MVP volume but a
--    visible bottleneck at agent-driven traffic.
CREATE INDEX IF NOT EXISTS idx_disclosure_created_at_with_ticker
    ON disclosure (created_at DESC)
    WHERE ticker IS NOT NULL;

-- 2. SummaryService.auditSuccessExists fires on every summarisation
--    attempt as the audit-success short-circuit guard. Without a
--    compound index Postgres uses idx_llm_audit_rcpt_no then filters
--    `success` post-fetch. Partial-on-success keeps the index small.
CREATE INDEX IF NOT EXISTS idx_llm_audit_rcpt_no_success_only
    ON llm_audit (rcpt_no)
    WHERE success = true;

-- 3. disclosure_summary.summary_en was VARCHAR(500). Gemini occasionally
--    produces COMPLEX-tier summaries that exceed 500 chars (rights
--    offerings with multiple sub-events, large M&A filings). The
--    server's 500-char cap caused a `value too long` integrity
--    violation, which committed the audit row (REQUIRES_NEW) but
--    rejected the summary insert. The retry scheduler then re-enqueued
--    the rcpt_no, the audit-success short-circuit fired, and the
--    summary was permanently orphaned. Widen to TEXT so legitimate
--    long summaries land; SummaryWriter still defensively truncates
--    at 800 chars to keep a soft contract with the prompt.
ALTER TABLE disclosure_summary ALTER COLUMN summary_en TYPE TEXT;
