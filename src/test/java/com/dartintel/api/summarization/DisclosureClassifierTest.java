package com.dartintel.api.summarization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the round-15c rule-based classifier. Each
 * parameterised case is one DART {@code report_nm} taken directly
 * from a high-frequency row in the 2026-05-29 production
 * {@code disclosure_summary} table — see the {@code DisclosureClassifier}
 * Javadoc for the derivation methodology. The expected
 * {@code eventType} / {@code importance} pair is what the LLM
 * produced for that row in cache, so the classifier passing this
 * suite means it produces the same shape as the LLM on the cases
 * that drive the bulk of {@code /recent} traffic.
 */
class DisclosureClassifierTest {

    @ParameterizedTest
    @CsvSource(textBlock = """
            # report_nm,                                         expectedEventType,        expectedImportance
            연결감사보고서 (2025.12),                                AUDIT_REPORT,             1
            감사보고서 (2025.12),                                    AUDIT_REPORT,             1
            분기보고서 (2026.03),                                    PERIODIC_REPORT,          1
            사업보고서 (2025.12),                                    PERIODIC_REPORT,          1
            임원ㆍ주요주주특정증권등소유상황보고서,                   MAJOR_SHAREHOLDER_FILING, 3
            주식등의대량보유상황보고서(일반),                         MAJOR_SHAREHOLDER_FILING, 3
            증권발행실적보고서,                                       DEBT_ISSUANCE,            5
            일괄신고추가서류(파생결합사채-주가연계파생결합사채),       DEBT_ISSUANCE,            5
            기업설명회(IR)개최(안내공시),                            IR_EVENT,                 3
            특수관계인으로부터자금차입,                                RELATED_PARTY_TRANSACTION, 5
            연결재무제표기준영업(잠정)실적(공정공시),                  PRELIMINARY_RESULTS,      5
            결산실적공시예고(안내공시),                                EARNINGS_PREVIEW,         5
            단일판매ㆍ공급계약체결,                                   SUPPLY_CONTRACT_SIGNED,   5
            단일판매ㆍ공급계약해지,                                   SUPPLY_CONTRACT_TERMINATED, 5
            주요사항보고서(유상증자결정),                              RIGHTS_OFFERING,          7
            특수관계인의유상증자참여,                                  RIGHTS_OFFERING,          7
            주요사항보고서(전환사채권발행결정),                        CONVERTIBLE_BOND_ISSUANCE, 7
            주요사항보고서(자기주식취득결정),                          TREASURY_STOCK_ACQUISITION, 7
            주요사항보고서(자기주식처분결정),                          TREASURY_STOCK_DISPOSAL,  6
            주요사항보고서(회사합병결정),                              MERGER,                   9
            합병등종료보고서(합병),                                   MERGER,                   9
            현금ㆍ현물배당결정,                                       DIVIDEND_DECISION,        5
            주주총회소집공고,                                         SHAREHOLDERS_MEETING,     3
            의결권대리행사권유참고서류,                                SHAREHOLDERS_MEETING,     3
            대표이사변경,                                             CEO_CHANGE,               7
            주권매매거래정지,                                         TRADING_SUSPENSION,       9
            주권매매거래정지해제,                                     TRADING_RESUMPTION,       5
            기타시장안내              (상장폐지 관련 이의신청서 접수),  DELISTING,                9
            주요사항보고서(회생절차개시신청),                          BANKRUPTCY,               9
            전환청구권행사,                                           CONVERTIBLE_BOND_CONVERSION, 5
            교환청구권행사,                                           CONVERTIBLE_BOND_CONVERSION, 5
            전환가액의조정,                                           CONVERSION_PRICE_ADJUSTMENT, 5
            사외이사의선임ㆍ해임또는중도퇴임에관한신고,                 BOARD_CHANGE,             3
            타법인주식및출자증권취득결정,                              ACQUISITION,              7
            소송등의제기,                                             LITIGATION,               7
            소송등의판결ㆍ결정(자율공시:일정금액미만의청구),           LITIGATION,               5
            주주명부폐쇄기간또는기준일설정,                           RECORD_DATE_NOTICE,       1
            주식소각결정,                                             TREASURY_STOCK_CANCELLATION, 5
            최대주주변경,                                             CONTROL_CHANGE,           7
            최대주주변경을수반하는주식담보제공계약체결,                CONTROL_CHANGE_PLEDGE,    7
            사채원리금미지급발생,                                     BOND_DEFAULT,             9
            타인에대한채무보증결정,                                   DEBT_GUARANTEE,           5
            자산유동화관련중요사항발생등보고서(기타),                  ASSET_BACKED_SECURITIES,  5
            대규모기업집단현황공시[연1회공시및1/4분기용(개별회사)],     CONGLOMERATE_DISCLOSURE,  3
            """)
    void knownProductionPatternsClassifyToExpectedShape(
            String reportNm, String expectedEventType, int expectedImportance
    ) {
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(reportNm, "005930");

        assertThat(c.eventType()).isEqualTo(expectedEventType);
        assertThat(c.importanceScore()).isEqualTo(expectedImportance);
        // tickerTags always carries the resolved ticker; sectorTags
        // stays empty in classifier v1 (no KRX sector join yet).
        assertThat(c.tickerTags()).containsExactly("005930");
        assertThat(c.sectorTags()).isEmpty();
        // actionableFor is non-null and non-empty for every matched
        // rule — at minimum carries "traders" or "long_term_investors".
        assertThat(c.actionableFor()).isNotEmpty();
    }

    @Test
    void revisionPrefixIsStrippedSoTheInnerRuleStillMatches() {
        // [기재정정] / [첨부정정] revisions of an earlier filing
        // should classify as the same event type as the original —
        // the LLM-cached data set treats them identically.
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(
                "[기재정정]주요사항보고서(유상증자결정)", "005930");

        assertThat(c.eventType()).isEqualTo("RIGHTS_OFFERING");
        assertThat(c.importanceScore()).isEqualTo(7);
    }

    @Test
    void revisionWithNoInnerMatchFallsBackToAmendment() {
        // No keyword in the inner body matches — the prefix tells us
        // it's a revision so AMENDMENT is the right bucket, distinct
        // from the OTHER fallback used for entirely novel filings.
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(
                "[기재정정]일부신규공시양식", "005930");

        assertThat(c.eventType()).isEqualTo("AMENDMENT");
        assertThat(c.importanceScore()).isEqualTo(3);
    }

    @Test
    void unrecognisedReportNmFallsBackToOther() {
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(
                "완전히새로운종류의공시", "005930");

        assertThat(c.eventType()).isEqualTo(DisclosureClassifier.FALLBACK_EVENT_TYPE);
        assertThat(c.importanceScore()).isEqualTo(DisclosureClassifier.FALLBACK_IMPORTANCE);
    }

    @Test
    void nullTickerProducesEmptyTickerTagsButStillMatches() {
        // Non-listed filer or unresolved corp_code — common for
        // delisted companies. The classification still runs; only
        // tickerTags goes empty.
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(
                "감사보고서 (2025.12)", null);

        assertThat(c.eventType()).isEqualTo("AUDIT_REPORT");
        assertThat(c.tickerTags()).isEmpty();
    }

    @Test
    void nullReportNmFallsBackGracefullyWithoutNpe() {
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(null, "005930");

        assertThat(c.eventType()).isEqualTo(DisclosureClassifier.FALLBACK_EVENT_TYPE);
        assertThat(c.importanceScore()).isEqualTo(DisclosureClassifier.FALLBACK_IMPORTANCE);
        assertThat(c.tickerTags()).containsExactly("005930");
    }

    @Test
    void blankReportNmFallsBackGracefullyWithoutNpe() {
        DisclosureClassifier.Classification c = DisclosureClassifier.classify("   ", "005930");

        assertThat(c.eventType()).isEqualTo(DisclosureClassifier.FALLBACK_EVENT_TYPE);
    }

    // ---- round-17a: numericExpectation pre-purchase signal ----

    @ParameterizedTest
    @CsvSource(textBlock = """
            # eventType,                    expected
            DIVIDEND_DECISION,              HIGH
            RIGHTS_OFFERING,                HIGH
            SUPPLY_CONTRACT_SIGNED,         HIGH
            CONVERTIBLE_BOND_ISSUANCE,      HIGH
            MERGER,                         HIGH
            TREASURY_STOCK_ACQUISITION,     HIGH
            PRELIMINARY_RESULTS,            HIGH
            MAJOR_SHAREHOLDER_FILING,       LOW
            RECORD_DATE_NOTICE,             LOW
            PERIODIC_REPORT,                LOW
            AUDIT_REPORT,                   LOW
            IR_EVENT,                       LOW
            SHAREHOLDERS_MEETING,           LOW
            OTHER,                          LOW
            """)
    void numericExpectationSplitsNumberBearingFromThinEventTypes(String eventType, String expected) {
        assertThat(DisclosureClassifier.numericExpectation(eventType)).isEqualTo(expected);
    }

    @Test
    void numericExpectationNullEventTypeIsLow() {
        assertThat(DisclosureClassifier.numericExpectation(null)).isEqualTo("LOW");
    }

    // ---- round-18: English filing-type label ----

    @ParameterizedTest
    @CsvSource(textBlock = """
            # eventType,                 expectedLabel
            MERGER,                      Merger Decision
            RIGHTS_OFFERING,             Rights Offering (Paid-in Capital Increase)
            TRADING_SUSPENSION,          Trading Suspension
            DIVIDEND_DECISION,           Dividend Decision
            MAJOR_SHAREHOLDER_FILING,    Major Shareholder Ownership Report
            CONGLOMERATE_DISCLOSURE,     Large Business Group Disclosure
            OTHER,                       Other Disclosure
            """)
    void eventLabelEnRendersFilingTypeInEnglish(String eventType, String expectedLabel) {
        assertThat(DisclosureClassifier.eventLabelEn(eventType)).isEqualTo(expectedLabel);
    }

    @Test
    void eventLabelEnFallsBackForNullAndUnknownEventTypes() {
        // Every row must carry an English description — an unmapped or
        // absent eventType degrades to the OTHER label rather than null,
        // so an English-only consumer never sees an empty field.
        assertThat(DisclosureClassifier.eventLabelEn(null)).isEqualTo("Other Disclosure");
        assertThat(DisclosureClassifier.eventLabelEn("NOT_A_REAL_EVENT_TYPE"))
                .isEqualTo("Other Disclosure");
    }

    @Test
    void everyClassifiableEventTypeHasAnEnglishLabel() {
        // Guards the map against drift: adding a rule to RULES without
        // a matching label would silently ship "Other Disclosure" for a
        // known event type. Drives every report_nm in the production
        // sample above through the classifier and asserts a specific
        // label came back.
        String[] productionReportNames = {
                "연결감사보고서 (2025.12)", "분기보고서 (2026.03)", "증권발행실적보고서",
                "기업설명회(IR)개최(안내공시)", "단일판매ㆍ공급계약체결", "주요사항보고서(유상증자결정)",
                "주요사항보고서(회사합병결정)", "현금ㆍ현물배당결정", "주주총회소집공고",
                "대표이사변경", "주권매매거래정지", "주요사항보고서(회생절차개시신청)",
                "타법인주식및출자증권취득결정", "소송등의제기", "최대주주변경",
                "사채원리금미지급발생", "타인에대한채무보증결정"
        };
        for (String reportNm : productionReportNames) {
            String eventType = DisclosureClassifier.classify(reportNm, "005930").eventType();
            assertThat(DisclosureClassifier.eventLabelEn(eventType))
                    .as("English label for eventType %s (from '%s')", eventType, reportNm)
                    .isNotEqualTo("Other Disclosure");
        }
    }

    @Test
    void mergerOutscoresAcquisitionWhenBothKeywordsAppear() {
        // Some merger filings also contain the substring "취득" —
        // ordering in RULES guarantees merger wins. The data set
        // shows merger at importance 9 and acquisitions at 7, so the
        // wrong order would systematically under-rate top-tier
        // corporate events.
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(
                "주요사항보고서(회사합병결정) 타법인주식 취득", "005930");

        assertThat(c.eventType()).isEqualTo("MERGER");
        assertThat(c.importanceScore()).isEqualTo(9);
    }

    @Test
    void tradingResumptionDoesNotGetCaughtByTradingSuspensionRule() {
        // "주권매매거래정지해제" is the resumption notice and must
        // NOT match the broader "주권매매거래정지" rule. RULES has
        // resumption ahead of suspension; this test pins that.
        DisclosureClassifier.Classification c = DisclosureClassifier.classify(
                "주권매매거래정지해제              (액면병합 주권 변경상장)", "005930");

        assertThat(c.eventType()).isEqualTo("TRADING_RESUMPTION");
        assertThat(c.importanceScore()).isEqualTo(5);
    }
}
