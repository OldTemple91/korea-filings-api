package com.dartintel.api.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<Disclosure, String> {

    boolean existsByRcptNo(String rcptNo);
}
