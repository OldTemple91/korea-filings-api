package com.dartintel.api.api.dto;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.summarization.DisclosureClassifier;
import com.dartintel.api.summarization.DisclosureSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RecentFilingDtoTest {

    private static Disclosure disclosure() {
        return new Disclosure(
                "20260722000123", "00126380", "삼성전자", "SAMSUNG ELECTRONICS CO,.LTD",
                "주요사항보고서(유상증자결정)", "삼성전자",
                LocalDate.of(2026, 7, 22), null, "005930"
        );
    }

    /**
     * Round-18d: classifier stubs cannot infer sector, so the enriched
     * free-feed row must OMIT sectorTags (unknown), never serialise an
     * empty array (reads as "known to be none"). The other enrichment
     * fields the classifier does populate stay present.
     */
    @Test
    void stubRowOmitsSectorTagsButKeepsClassifierFields() {
        Disclosure d = disclosure();
        DisclosureSummary stub = DisclosureSummary.fromClassification(
                d.getRcptNo(),
                DisclosureClassifier.classify(d.getReportNm(), d.getTicker()));

        RecentFilingDto dto = RecentFilingDto.from(d, stub);

        // when & then
        assertThat(dto.sectorTags()).isNull();
        assertThat(dto.eventType()).isEqualTo("RIGHTS_OFFERING");
        assertThat(dto.tickerTags()).containsExactly("005930");
        assertThat(dto.actionableFor()).isNotEmpty();
        assertThat(dto.reportNmEn()).isEqualTo("Rights Offering (Paid-in Capital Increase)");
        assertThat(dto.corpNameEn()).isEqualTo("SAMSUNG ELECTRONICS CO,.LTD");
    }
}
