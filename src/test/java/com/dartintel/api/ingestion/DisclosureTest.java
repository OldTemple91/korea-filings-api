package com.dartintel.api.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DisclosureTest {

    // ---- round-18: DART fixed-width padding normalisation ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            # raw                                                      | expected
            주요사항보고서(유상증자결정)                                | 주요사항보고서(유상증자결정)
            주요사항보고서(유상증자결정)                                | 주요사항보고서(유상증자결정)
            주권매매거래정지              (무상증자)                    | 주권매매거래정지 (무상증자)
            기타시장안내              (상장폐지 관련 이의신청서 접수)   | 기타시장안내 (상장폐지 관련 이의신청서 접수)
            """)
    void normalizeReportNmCollapsesTrailingAndInteriorPadding(String raw, String expected) {
        assertThat(Disclosure.normalizeReportNm(raw)).isEqualTo(expected);
    }

    @Test
    void normalizeReportNmStripsTrailingRun() {
        // Trailing run written explicitly — CsvSource trims cell ends,
        // so the padded-tail case cannot live in the text block above.
        assertThat(Disclosure.normalizeReportNm("현금ㆍ현물배당결정              "))
                .isEqualTo("현금ㆍ현물배당결정");
    }

    @Test
    void normalizeReportNmIsNullSafe() {
        assertThat(Disclosure.normalizeReportNm(null)).isNull();
    }
}
