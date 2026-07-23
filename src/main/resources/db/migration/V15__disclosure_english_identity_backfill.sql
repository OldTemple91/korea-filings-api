-- Round-18: make the API answer in English.
--
-- Two data defects fixed here, both pure SQL — no LLM call, no cost:
--
-- 1. disclosure.corp_name_eng existed since V1 but was written as NULL
--    on every row (DART's /list.json carries only the Korean
--    corp_name). Consequences: the free /recent feed described every
--    filing in Korean on an English-first product, the paid summary
--    payload had no company name at all, and the Gemini prompt
--    received the literal string "n/a" for the English name on all
--    ~6.2k summaries generated to date. company.name_en is populated
--    for 100% of the KRX directory, so the fix is a join.
--
-- 2. DART pads report_nm to a fixed width with trailing spaces, which
--    leaked into the JSON payload and broke consumer-side string
--    matching.
--
-- Ingestion is fixed in DartPollingScheduler for new rows; this
-- migration repairs the existing corpus. Filers absent from the KRX
-- directory (funds, ELS/SPC vehicles — ~34% of DART volume) correctly
-- keep a NULL English name; they have no ticker either and are already
-- filtered out of the by-ticker product path.

UPDATE disclosure d
   SET corp_name_eng = c.name_en
  FROM company c
 WHERE c.corp_code = d.corp_code
   AND d.corp_name_eng IS NULL
   AND c.name_en IS NOT NULL;

UPDATE disclosure
   SET report_nm = btrim(report_nm)
 WHERE report_nm <> btrim(report_nm);
