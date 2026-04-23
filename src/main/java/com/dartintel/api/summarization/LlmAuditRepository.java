package com.dartintel.api.summarization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmAuditRepository extends JpaRepository<LlmAudit, Long> {

    List<LlmAudit> findByRcptNoOrderByCreatedAtDesc(String rcptNo);
}
