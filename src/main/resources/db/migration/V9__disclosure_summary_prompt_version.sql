-- V9 — track which version of the prompt produced each summary.
--
-- The cache invariant ("summaries are immutable once generated") holds
-- per-prompt. When the prompt changes (v1.2 adds keyFacts; future
-- prompt-tuning passes change tone or schema), we need to scope the
-- impact: re-summarise only rows whose prompt_version is below the
-- new constant, not the whole table.
--
-- Default 1 — every existing row was produced by the v1.1 prompt
-- (the only prompt to date). When the prompt is changed, bump
-- SummaryWriter.PROMPT_VERSION and the retry scheduler can re-enqueue
-- rows with prompt_version < current at low priority.
ALTER TABLE disclosure_summary
    ADD COLUMN prompt_version SMALLINT NOT NULL DEFAULT 1;

-- Backfill is implicit via the DEFAULT clause; future inserts must
-- pass the current PROMPT_VERSION explicitly so the column stays
-- meaningful as new prompts ship.
COMMENT ON COLUMN disclosure_summary.prompt_version IS
    'Monotonically-increasing version of the LLM prompt that generated this row. '
    'Bump in code when the prompt changes; retry scheduler re-summarises '
    'rows below the current version at low priority.';
