package com.dartintel.api.summarization.classifier;

import com.dartintel.api.summarization.Complexity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexityClassifierTest {

    private ComplexityClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ComplexityClassifier();
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            # COMPLEX — corporate-restructuring filings
            '회사합병결정', COMPLEX
            '회사분할결정', COMPLEX
            '지주회사 전환결정', COMPLEX
            '주식교환 결정', COMPLEX
            '영업양수결정', COMPLEX
            '영업양도결정', COMPLEX
            '대규모내부거래에 대한 이사회 의결 공시', COMPLEX

            # MEDIUM — capital-structure / cash-return events
            '주요사항보고서(유상증자결정)', MEDIUM
            '무상증자결정', MEDIUM
            '주요사항보고서(전환사채권발행결정)', MEDIUM
            '신주인수권부사채권 발행결정', MEDIUM
            '교환사채권 발행결정', MEDIUM
            '주요사항보고서(감자결정)', MEDIUM
            '주식소각결정', MEDIUM
            '현금ㆍ현물배당결정', MEDIUM
            '자기주식취득 결정', MEDIUM
            '자기주식 처분 결정', MEDIUM
            '자사주취득 신탁계약 체결 결정', MEDIUM
            '자사주처분 신탁계약 해지 결정', MEDIUM

            # SIMPLE — routine and informational
            '기업설명회(IR)개최(안내공시)', SIMPLE
            '연결재무제표기준영업(잠정)실적(공정공시)', SIMPLE
            '연결감사보고서 (2025.12)', SIMPLE
            '주식등의대량보유상황보고서(약식)', SIMPLE
            '임원ㆍ주요주주특정증권등소유상황보고서', SIMPLE
            '주식명의개서정지(주주명부폐쇄)', SIMPLE
            """, useHeadersInDisplayName = false, ignoreLeadingAndTrailingWhitespace = true)
    void classifiesReportNmIntoExpectedComplexity(String reportNm, Complexity expected) {
        assertThat(classifier.classify(reportNm)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "''",
            "'   '"
    })
    void blankReportNmFallsBackToSimple(String reportNm) {
        assertThat(classifier.classify(reportNm)).isEqualTo(Complexity.SIMPLE);
    }

    @org.junit.jupiter.api.Test
    void nullReportNmFallsBackToSimple() {
        assertThat(classifier.classify(null)).isEqualTo(Complexity.SIMPLE);
    }

    @org.junit.jupiter.api.Test
    void complexKeywordTrumpsMediumKeywordWhenBothPresent() {
        // e.g. a merger filing that also mentions a dividend should classify as COMPLEX
        assertThat(classifier.classify("회사합병결정 및 배당계획 통지")).isEqualTo(Complexity.COMPLEX);
    }
}
