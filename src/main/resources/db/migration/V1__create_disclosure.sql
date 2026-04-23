CREATE TABLE disclosure (
    rcpt_no        VARCHAR(14)  PRIMARY KEY,
    corp_code      VARCHAR(8)   NOT NULL,
    corp_name      VARCHAR(200) NOT NULL,
    corp_name_eng  VARCHAR(200),
    report_nm      VARCHAR(500) NOT NULL,
    flr_nm         VARCHAR(200) NOT NULL,
    rcept_dt       DATE         NOT NULL,
    rm             VARCHAR(20),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_disclosure_rcept_dt_rcpt_no
    ON disclosure (rcept_dt DESC, rcpt_no DESC);

CREATE INDEX idx_disclosure_corp_code
    ON disclosure (corp_code);

COMMENT ON TABLE disclosure IS 'Raw DART disclosure metadata, one row per rcpt_no';
COMMENT ON COLUMN disclosure.rcpt_no IS 'DART receipt number, 14 digits';
COMMENT ON COLUMN disclosure.corp_code IS 'DART corporate code, 8 digits';
COMMENT ON COLUMN disclosure.rcept_dt IS 'Receipt date, parsed from DART yyyymmdd format';
