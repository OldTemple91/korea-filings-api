package com.dartintel.api.ingestion;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DisclosureRepository extends JpaRepository<Disclosure, String> {

    boolean existsByRcptNo(String rcptNo);

    @Query("""
            SELECT d.rcptNo FROM Disclosure d
            WHERE NOT EXISTS (
                SELECT 1 FROM com.dartintel.api.summarization.DisclosureSummary s
                WHERE s.rcptNo = d.rcptNo
            )
            ORDER BY d.rceptDt DESC, d.rcptNo DESC
            """)
    List<String> findRcptNosMissingSummary(Pageable pageable);

    /**
     * Recent filings for one ticker, newest first. Backed by the
     * partial index {@code idx_disclosure_ticker_dt} so even at
     * 100k+ disclosures across the whole table, this is a single
     * b-tree range scan.
     */
    @Query("""
            SELECT d FROM Disclosure d
            WHERE d.ticker = :ticker
            ORDER BY d.rceptDt DESC, d.rcptNo DESC
            """)
    List<Disclosure> findByTickerRecent(@Param("ticker") String ticker, Pageable pageable);

    /**
     * Recent filings across every listed company since {@code threshold}.
     * Used by the free {@code /v1/disclosures/recent} endpoint to give
     * agents a "what's hot right now" feed. The {@code ticker IS NOT NULL}
     * filter excludes filings from delisted/foreign filers that the
     * by-ticker path can't follow up on anyway.
     */
    @Query("""
            SELECT d FROM Disclosure d
            WHERE d.ticker IS NOT NULL
              AND d.createdAt >= :since
            ORDER BY d.createdAt DESC, d.rcptNo DESC
            """)
    List<Disclosure> findRecentSince(@Param("since") Instant since, Pageable pageable);

    /**
     * Round-19: the same free feed narrowed to one ticker, so an agent
     * can watch a single company without paying. The paid product is
     * the English summary text, not the knowledge that a filing exists
     * — gating "did anything happen at 005930?" behind a wallet made
     * the free tier a prerequisite for buying rather than something
     * usable on its own.
     *
     * <p>Uses the same {@code createdAt} watermark as
     * {@link #findRecentSince} (ingestion time, not DART's filing date)
     * so the two paths agree on what "recent" means; {@code rcept_dt}
     * is date-only and would bucket a whole day together.
     */
    @Query("""
            SELECT d FROM Disclosure d
            WHERE d.ticker = :ticker
              AND d.createdAt >= :since
            ORDER BY d.createdAt DESC, d.rcptNo DESC
            """)
    List<Disclosure> findRecentSinceForTicker(@Param("ticker") String ticker,
                                              @Param("since") Instant since,
                                              Pageable pageable);
}
