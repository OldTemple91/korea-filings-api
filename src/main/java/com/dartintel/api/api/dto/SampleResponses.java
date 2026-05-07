package com.dartintel.api.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Single source of truth for the "what does a paid response look like"
 * sample shape, so {@code /v1/pricing}, {@code /.well-known/x402},
 * {@code /v1/disclosures/sample}, and the OpenAPI examples all agree.
 *
 * <p>The example was lifted from the first body-aware Base mainnet
 * paid call we ran on round-11 ship day (2026-05-06,
 * {@code rcpt_no=20260430800106}, settled at
 * {@code 0x5a0403ae18db0394cb6121e6ca26aac75f44f0bb8a5d0db5dab61e84d9995a20}).
 * Showing real on-chain proof beats a hand-crafted illustration —
 * indexers and skeptical agent builders can verify the BaseScan link
 * before deciding whether to integrate.
 *
 * <p>Constants (not config-driven) — the value is "this exact
 * response shape, frozen". If we want to bump the example to a
 * fresher filing, change the constants here and every surface
 * updates in lockstep.
 */
public final class SampleResponses {

    private SampleResponses() {
    }

    /**
     * The 14-digit DART receipt number behind the sample summary.
     * Maps to Samsung Electronics' 2026-Q1 quarterly cash dividend
     * decision, filed 2026-04-30.
     */
    public static final String SAMPLE_RCPT_NO = "20260430800106";

    /**
     * BaseScan URL for the on-chain settlement that produced this
     * summary on round-11 ship day. Linking the proof keeps the
     * sample honest — agents can audit the mainnet tx instead of
     * trusting the surface.
     */
    public static final String SAMPLE_SETTLEMENT_TX_URL =
            "https://basescan.org/tx/0x5a0403ae18db0394cb6121e6ca26aac75f44f0bb8a5d0db5dab61e84d9995a20";

    /**
     * The paid response an agent receives from
     * {@code GET /v1/disclosures/summary?rcptNo=20260430800106}.
     * Built from the disclosure_summary row that round-11 lazy +
     * body fetch produced — body 726 chars Korean → summaryEn 301
     * chars English carrying every quantitative fact (per-share KRW,
     * total payout, dividend yields, record date, payment date)
     * that the prior metadata-only summary had to defer to "the
     * filing body" itself.
     */
    public static DisclosureSummaryDto sampleSummary() {
        return new DisclosureSummaryDto(
                SAMPLE_RCPT_NO,
                "Samsung Electronics decided on a quarterly cash dividend of " +
                        "KRW 372 per common share and KRW 372 per preferred share, " +
                        "totaling KRW 2,453,315,636,604. The dividend yield is 0.2% " +
                        "for common shares and 0.3% for preferred shares. The record " +
                        "date is March 31, 2026, with payment scheduled for May 29, 2026.",
                7,
                "DIVIDEND_DECISION",
                List.of("Technology Hardware & Equipment"),
                List.of("005930"),
                List.of("traders", "long_term_investors"),
                Instant.parse("2026-05-06T07:29:45.911215Z")
        );
    }
}
