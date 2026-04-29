-- Denormalise the disclosure → company join.
--
-- Every paid by-ticker query needs to scan disclosures by ticker. Doing
-- that via a join against company (corp_code → ticker) on every request
-- adds a hash join to a hot path. Since corp_code → ticker is stable
-- once a company is listed, we copy the ticker into the disclosure row
-- at ingestion time and index (ticker, rcept_dt) for fast paginated
-- "recent N filings for this ticker" queries.
ALTER TABLE disclosure ADD COLUMN ticker VARCHAR(6);

-- Backfill existing rows from the company table. Disclosures whose
-- corp_code is not in company (e.g. delisted entities, or entries we
-- haven't synced yet) keep ticker NULL — they're invisible to the
-- by-ticker endpoint, which is correct.
UPDATE disclosure d
SET ticker = c.ticker
FROM company c
WHERE d.corp_code = c.corp_code;

CREATE INDEX idx_disclosure_ticker_dt ON disclosure (ticker, rcept_dt DESC)
    WHERE ticker IS NOT NULL;
