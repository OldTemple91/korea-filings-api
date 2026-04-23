CREATE TABLE disclosure_summary (
    rcpt_no          VARCHAR(14)   PRIMARY KEY REFERENCES disclosure(rcpt_no) ON DELETE CASCADE,
    summary_en       VARCHAR(500)  NOT NULL,
    importance_score INTEGER       NOT NULL CHECK (importance_score BETWEEN 1 AND 10),
    event_type       VARCHAR(50)   NOT NULL,
    sector_tags      JSONB         NOT NULL DEFAULT '[]'::jsonb,
    ticker_tags      JSONB         NOT NULL DEFAULT '[]'::jsonb,
    actionable_for   JSONB         NOT NULL DEFAULT '[]'::jsonb,
    model_used       VARCHAR(100)  NOT NULL,
    input_tokens     INTEGER       NOT NULL CHECK (input_tokens >= 0),
    output_tokens    INTEGER       NOT NULL CHECK (output_tokens >= 0),
    cost_usd         NUMERIC(10,8) NOT NULL CHECK (cost_usd >= 0),
    generated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_disclosure_summary_event_type
    ON disclosure_summary (event_type);

CREATE INDEX idx_disclosure_summary_importance
    ON disclosure_summary (importance_score DESC, generated_at DESC);

COMMENT ON TABLE disclosure_summary IS 'AI-generated English summary keyed 1:1 to disclosure.rcpt_no';
COMMENT ON COLUMN disclosure_summary.event_type IS 'Canonical UPPER_SNAKE_CASE label (RIGHTS_OFFERING, MERGER, ...)';
COMMENT ON COLUMN disclosure_summary.cost_usd IS 'USD cost of the LLM call that produced this summary';
