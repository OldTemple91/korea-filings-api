package com.dartintel.api.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DisclosureRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DisclosureRepository repository;

    @Test
    void savesAndReadsBackADisclosureWithKoreanText() {
        Disclosure d = new Disclosure(
                "20260423000001",
                "00126380",
                "삼성전자",
                "Samsung Electronics Co., Ltd.",
                "주요사항보고서(유상증자결정)",
                "삼성전자",
                LocalDate.of(2026, 4, 23),
                "유"
        );
        repository.saveAndFlush(d);

        Optional<Disclosure> loaded = repository.findById("20260423000001");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getCorpName()).isEqualTo("삼성전자");
        assertThat(loaded.get().getCorpNameEng()).isEqualTo("Samsung Electronics Co., Ltd.");
        assertThat(loaded.get().getReportNm()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(loaded.get().getRceptDt()).isEqualTo(LocalDate.of(2026, 4, 23));
        assertThat(loaded.get().getRm()).isEqualTo("유");
    }

    @Test
    void hibernateAuditingPopulatesTimestamps() {
        Disclosure d = new Disclosure(
                "20260423000002",
                "00164742",
                "한국가스공사",
                null,
                "현금ㆍ현물배당결정",
                "한국가스공사",
                LocalDate.of(2026, 4, 23),
                null
        );

        repository.saveAndFlush(d);

        Disclosure loaded = repository.findById("20260423000002").orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getCorpNameEng()).isNull();
        assertThat(loaded.getRm()).isNull();
    }

    @Test
    void existsByRcptNoReturnsFalseForMissingRcptNo() {
        assertThat(repository.existsByRcptNo("99999999999999")).isFalse();
    }
}
