package com.dartintel.api.company;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, String> {

    Optional<Company> findByCorpCode(String corpCode);

    /**
     * Trigram-similarity search against both Korean and English names
     * with an exact-ticker shortcut. Postgres' {@code pg_trgm} GIN index
     * handles the {@code ILIKE %q%} predicates fast even at the full
     * KRX ~2.5k-row table size; the trigram-similarity ordering puts
     * the closest matches first ({@code "Samsung Electronics"} for
     * the query {@code "samsung"} ranks above {@code "Samsung C&T"}).
     *
     * <p>The query intentionally accepts an exact six-digit ticker as
     * input too so a single endpoint serves both name and ticker
     * lookups — the agent does not have to pre-classify its query.
     */
    @Query(value = """
            SELECT * FROM company
            WHERE ticker = :q
               OR name_kr ILIKE '%' || :q || '%'
               OR name_en ILIKE '%' || :q || '%'
            ORDER BY
                CASE WHEN ticker = :q THEN 0 ELSE 1 END,
                GREATEST(
                    similarity(name_kr, :q),
                    similarity(coalesce(name_en, ''), :q)
                ) DESC,
                ticker
            """, nativeQuery = true)
    List<Company> search(@Param("q") String query, Pageable pageable);
}
