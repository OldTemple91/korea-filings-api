-- disclosure.body
--
-- Adds a TEXT column to cache the per-filing body fetched from
-- DART's `/api/document.xml` endpoint. v1.1 with body fetch lifts
-- summarisation from a 23-character report_nm headline to the full
-- filing text — what previously came back as "details are in the
-- filing body" can now be extracted into the summary itself.
--
-- Storage shape:
--
--   - TEXT (unbounded). Average filing body is ~5 KB plain text after
--     the parser strips HTML/XBRL markup; p99 ~30 KB; the parser caps
--     at 20,000 characters before insert. 5,998 existing rows × 20 KB
--     ≈ 120 MB worst case for a full backfill — trivial for the
--     production VPS's Postgres.
--
--   - NULL = body not fetched yet. Two ingestion modes coexist via
--     this nullability:
--       (a) eager (legacy / current): summary already exists with body
--           IS NULL — kept for backward compat with the 5,998 rows
--           summarised before this migration.
--       (b) lazy (new default after V13): SummaryService.summarize is
--           triggered on the first paid call for a fresh rcptNo;
--           body is fetched, persisted here, then fed to the LLM.
--
--   - No index. Body is fetched-by-rcpt_no via the entity's primary
--     key; full-text search on body is out of scope for v1.x.

ALTER TABLE disclosure
    ADD COLUMN body TEXT;
