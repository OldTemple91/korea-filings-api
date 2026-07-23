-- Round-18f: repair LLM-hallucinated ticker_tags.
--
-- The LLM prompt asked the model to emit tickerTags and the writer
-- stored the model's answer verbatim — but no LLM reliably knows KRX
-- ticker codes, so 3,504 of 6,563 LLM-generated rows (53%) carried a
-- wrong or empty ticker while disclosure.ticker (resolved
-- deterministically from DART's corp_code at ingestion) held the right
-- one. Caught by the first live paid verification call after round-18:
-- a Samsung Biologics [207940] filing came back tagged ["028260"]
-- (Samsung C&T).
--
-- SummaryWriter now writes the deterministic ticker; this migration
-- fixes the stored corpus. Summary text is untouched — this is
-- metadata correction, not regeneration. Filings whose issuer has no
-- KRX ticker get an empty array (an invented ticker is worse than
-- none).

UPDATE disclosure_summary s
   SET ticker_tags = to_jsonb(ARRAY[d.ticker])
  FROM disclosure d
 WHERE d.rcpt_no = s.rcpt_no
   AND s.summary_en IS NOT NULL
   AND d.ticker IS NOT NULL
   AND NOT (s.ticker_tags::jsonb ? d.ticker);

UPDATE disclosure_summary s
   SET ticker_tags = '[]'::jsonb
  FROM disclosure d
 WHERE d.rcpt_no = s.rcpt_no
   AND s.summary_en IS NOT NULL
   AND d.ticker IS NULL
   AND s.ticker_tags::text <> '[]';
