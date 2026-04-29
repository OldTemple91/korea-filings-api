package com.dartintel.api.api.dto;

import com.dartintel.api.ingestion.Disclosure;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Lightweight, free-tier representation of a DART filing — metadata
 * only, no AI summary. Returned by the {@code /v1/disclosures/recent}
 * endpoint so agents can browse what is happening today and decide
 * which filings are worth a paid {@code by-ticker} or {@code summary}
 * call.
 *
 * <p>The {@code report_nm} stays in Korean because that is its
 * canonical form on DART, and any agent that wants an English version
 * can pay for the AI summary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentFilingDto(
        String rcptNo,
        String ticker,
        String corpName,
        String reportNm,
        LocalDate rceptDt
) {

    public static RecentFilingDto from(Disclosure d) {
        return new RecentFilingDto(
                d.getRcptNo(),
                d.getTicker(),
                d.getCorpName(),
                d.getReportNm(),
                d.getRceptDt()
        );
    }
}
