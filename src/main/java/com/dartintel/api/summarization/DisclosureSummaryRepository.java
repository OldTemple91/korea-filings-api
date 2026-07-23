package com.dartintel.api.summarization;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DisclosureSummaryRepository extends JpaRepository<DisclosureSummary, String> {

    boolean existsByRcptNo(String rcptNo);

    /**
     * Classifier stubs (rows the round-15c ingestion wrote with
     * {@code summary_en = NULL}) at or above an importance floor,
     * newest first — {@code rcpt_no} is date-prefixed so descending id
     * order is descending filing date. This is the round-18d backfill
     * selection: since 15c every ingested filing has a summary row, so
     * the older "summary row missing entirely" predicate
     * ({@code DisclosureRepository.findRcptNosMissingSummary}) matches
     * nothing ingested after 15c and stays only for the retry
     * scheduler's orphan sweep.
     */
    @Query("""
            SELECT s.rcptNo FROM DisclosureSummary s
            WHERE s.summaryEn IS NULL
              AND s.importanceScore >= :minImportance
            ORDER BY s.rcptNo DESC
            """)
    List<String> findStubRcptNos(@Param("minImportance") int minImportance, Pageable pageable);

    /**
     * One page of classifier stubs for the round-18e reclassification
     * sweep. Ordered by id so offset paging is stable — reclassified
     * rows keep {@code summary_en IS NULL} and therefore keep matching
     * the predicate, so pages never shift mid-sweep.
     */
    List<DisclosureSummary> findBySummaryEnIsNullOrderByRcptNo(Pageable pageable);
}
