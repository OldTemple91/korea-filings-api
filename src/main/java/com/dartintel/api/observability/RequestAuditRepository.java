package com.dartintel.api.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Repository for {@link RequestAudit}. Used by
 * {@link RequestAuditPersister} for bulk inserts and the nightly
 * pruning sweep. Custom analytics queries live in
 * {@code docs/ANALYTICS.md} — kept out of code so the SQL is reusable
 * from {@code docker exec dartintel-postgres psql} without a JVM
 * dependency.
 */
@Repository
public interface RequestAuditRepository extends JpaRepository<RequestAudit, Long> {

    /**
     * Deletes every row older than {@code cutoff}. Returns the number
     * of rows removed for the persister's heartbeat log.
     *
     * <p>{@code @Modifying} is required for any non-SELECT JPQL.
     * {@code @Transactional} is declared here (not on the persister)
     * so the prune runs in its own short transaction without hooking
     * into whatever scheduling thread invoked it.
     */
    @Modifying
    @Transactional
    @Query("delete from RequestAudit a where a.ts < :cutoff")
    int deleteByTsBefore(@Param("cutoff") Instant cutoff);

    long countByTsAfter(Instant since);
}
