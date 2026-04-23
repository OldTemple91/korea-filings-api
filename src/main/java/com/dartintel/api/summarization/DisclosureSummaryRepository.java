package com.dartintel.api.summarization;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureSummaryRepository extends JpaRepository<DisclosureSummary, String> {

    boolean existsByRcptNo(String rcptNo);
}
