package com.dartintel.api.ingestion;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
