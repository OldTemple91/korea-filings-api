-- Round-18g: capture the Referer header on audited requests.
--
-- Two months of traffic forensics ended every inflow question with
-- "referrer is not captured — attribution is inference from
-- first-touch path". Added ahead of the HN / GeekNews launches so
-- arrival channels become data. NULL for the (majority) clients that
-- send no header: bots, SDKs, direct API calls.
ALTER TABLE request_audit ADD COLUMN referer VARCHAR(512);
