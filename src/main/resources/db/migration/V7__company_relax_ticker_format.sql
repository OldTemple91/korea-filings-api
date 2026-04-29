-- The ^[0-9]{6}$ check on company.ticker rejects KRX ticker codes
-- that contain letters — SPAC tickers in particular embed a "Y" or
-- other letter in the middle (e.g. "0068Y0" for BNK SPAC #3). KRX
-- preferred-share series and KONEX also occasionally use 7-character
-- codes. Relax the constraint to accept any 5-7 character alphanumeric
-- string in uppercase. corp_code (DART internal) stays strictly numeric.
ALTER TABLE company DROP CONSTRAINT company_ticker_format;
ALTER TABLE company
    ADD CONSTRAINT company_ticker_format CHECK (ticker ~ '^[0-9A-Z]{5,7}$');

-- Bump the column width from 6 → 7 to make room for the wider variants
-- without future migrations.
ALTER TABLE company ALTER COLUMN ticker TYPE VARCHAR(7);
ALTER TABLE disclosure ALTER COLUMN ticker TYPE VARCHAR(7);
