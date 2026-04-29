-- Korean listed-company directory.
--
-- Sourced from DART's corpCode.xml endpoint, which returns ~85k entries
-- covering every entity that has ever filed with the FSS — listed,
-- delisted, and non-listed alike. We only persist the rows whose
-- stock_code is non-empty (~2,500 currently listed on KOSPI / KOSDAQ /
-- KONEX). The corp_code is DART's internal 8-digit identifier and is
-- the join key to the disclosure table; the ticker is what humans and
-- AI agents actually search by.
CREATE TABLE company (
    ticker             VARCHAR(6)   PRIMARY KEY,
    corp_code          VARCHAR(8)   NOT NULL UNIQUE,
    name_kr            VARCHAR(200) NOT NULL,
    name_en            VARCHAR(300),
    market             VARCHAR(20),                 -- KOSPI / KOSDAQ / KONEX (best effort)
    last_modified_at   DATE         NOT NULL,        -- DART's modify_date
    synced_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT company_ticker_format CHECK (ticker ~ '^[0-9]{6}$'),
    CONSTRAINT company_corp_code_format CHECK (corp_code ~ '^[0-9]{8}$')
);

-- Trigram indexes power the GET /v1/companies?q= search by Korean and
-- English name. pg_trgm ships with PostgreSQL 16 — no superuser needed.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_company_name_kr_trgm ON company USING gin (name_kr gin_trgm_ops);
CREATE INDEX idx_company_name_en_trgm ON company USING gin (name_en gin_trgm_ops);
CREATE INDEX idx_company_corp_code   ON company (corp_code);
