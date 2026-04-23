package com.dartintel.api.summarization;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DisclosureSummaryRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DisclosureRepository disclosureRepository;

    @Autowired
    private DisclosureSummaryRepository summaryRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void roundTripsSummaryWithJsonbArrays() {
        Disclosure d = new Disclosure(
                "20260423000999", "00126380", "삼성전자", null,
                "주요사항보고서(유상증자결정)", "삼성전자",
                LocalDate.of(2026, 4, 23), "유"
        );
        disclosureRepository.saveAndFlush(d);

        DisclosureSummary s = new DisclosureSummary(
                "20260423000999",
                "Samsung announced a rights offering decision.",
                9,
                "RIGHTS_OFFERING",
                List.of("Information Technology", "Semiconductors & Semiconductor Equipment"),
                List.of("005930"),
                List.of("traders", "long_term_investors"),
                "gemini-2.5-flash-lite",
                142,
                89,
                new BigDecimal("0.00005000")
        );
        summaryRepository.saveAndFlush(s);

        Optional<DisclosureSummary> loaded = summaryRepository.findById("20260423000999");
        assertThat(loaded).isPresent();
        DisclosureSummary actual = loaded.get();
        assertThat(actual.getSummaryEn()).startsWith("Samsung announced");
        assertThat(actual.getImportanceScore()).isEqualTo(9);
        assertThat(actual.getEventType()).isEqualTo("RIGHTS_OFFERING");
        assertThat(actual.getSectorTags())
                .containsExactly("Information Technology", "Semiconductors & Semiconductor Equipment");
        assertThat(actual.getTickerTags()).containsExactly("005930");
        assertThat(actual.getActionableFor()).containsExactly("traders", "long_term_investors");
        assertThat(actual.getModelUsed()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(actual.getInputTokens()).isEqualTo(142);
        assertThat(actual.getOutputTokens()).isEqualTo(89);
        assertThat(actual.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.00005000"));
        assertThat(actual.getGeneratedAt()).isNotNull();
    }

    @Test
    void cascadesDeleteFromDisclosure() {
        Disclosure d = new Disclosure(
                "20260423001000", "00126380", "삼성전자", null,
                "기업설명회(IR)개최(안내공시)", "삼성전자",
                LocalDate.of(2026, 4, 23), null
        );
        disclosureRepository.saveAndFlush(d);
        summaryRepository.saveAndFlush(new DisclosureSummary(
                "20260423001000",
                "Samsung announced an IR briefing.",
                3, "IR_EVENT",
                List.of("Information Technology"), List.of("005930"), List.of("long_term_investors"),
                "gemini-2.5-flash-lite", 80, 30, new BigDecimal("0.00002000")
        ));

        disclosureRepository.deleteById("20260423001000");
        em.flush();
        em.clear();  // drop L1 cache so the next findById actually hits the DB

        assertThat(summaryRepository.findById("20260423001000")).isEmpty();
    }

    @Test
    void existsByRcptNoReflectsPersistedState() {
        assertThat(summaryRepository.existsByRcptNo("20260423999999")).isFalse();
    }
}
