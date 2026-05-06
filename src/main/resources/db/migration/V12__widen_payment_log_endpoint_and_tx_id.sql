-- Widen two more payment_log columns that have the same latent
-- failure shape as signature_hash before V11.
--
-- (a) facilitator_tx_id — was VARCHAR(80). The Coinbase CDP
--     facilitator returns Ethereum tx hashes today (66 chars: 0x +
--     64 hex), comfortably under the cap. But x402 v2 leaves the
--     transaction reference format up to the facilitator, and a
--     future CDP response shape might prefix the hash with a CAIP-2
--     identifier (e.g. "eip155:8453:0xabcd…" = 79 chars) or wrap it
--     in a structured form. With only 14 characters of headroom,
--     any such change silently 22001's and falls into the "integrity
--     violation (NOT duplicate)" branch added in V11 — improvement
--     over the V10-era silent drop, but the row is still missing
--     and the operator has to chase it manually. Widen to 200 so
--     the column is decoupled from the facilitator's wire format.
--
-- (b) endpoint — was VARCHAR(200). Today's longest endpoint
--     "/v1/disclosures/by-ticker?ticker=XXXXXX&limit=50" is 47
--     chars. v1.2's planned /v1/disclosures/deep?rcptNo=… stays
--     under 60. But endpoint is the full URI including query string,
--     so any future endpoint accepting structured query parameters
--     (e.g. a filter / sector-event lookup with multiple constraints)
--     could blow past 200 quickly. Same class of latent bug: silently
--     reconcile-failures on a path that is otherwise working. Widen
--     to 500.
--
-- Postgres treats VARCHAR length as a stored type modifier, so both
-- ALTERs are metadata-only and run online with no table rewrite.

ALTER TABLE payment_log
    ALTER COLUMN facilitator_tx_id TYPE VARCHAR(200);

ALTER TABLE payment_log
    ALTER COLUMN endpoint TYPE VARCHAR(500);
