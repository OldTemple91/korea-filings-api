-- Round-18 follow-up: V15 stripped only leading/trailing padding, but
-- DART also pads report_nm INTERIOR runs before a parenthetical remark
-- ("주권매매거래정지              (무상증자)") — live inspection right
-- after the V15 deploy found 2,012 such rows still shipping multi-space
-- runs into the JSON payload. Collapse every whitespace run to a single
-- space; ingestion applies the same normalisation to new rows via
-- Disclosure.normalizeReportNm.
UPDATE disclosure
   SET report_nm = btrim(regexp_replace(report_nm, '\s+', ' ', 'g'))
 WHERE report_nm ~ '\s\s' OR report_nm <> btrim(report_nm);
