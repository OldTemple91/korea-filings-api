package com.dartintel.api.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DartListResponse(
        String status,
        String message,
        @JsonProperty("page_no") Integer pageNo,
        @JsonProperty("page_count") Integer pageCount,
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("total_page") Integer totalPage,
        List<DartFiling> list
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DartFiling(
            @JsonProperty("rcept_no") String rcptNo,
            @JsonProperty("corp_code") String corpCode,
            @JsonProperty("corp_name") String corpName,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("corp_cls") String corpCls,
            @JsonProperty("report_nm") String reportNm,
            @JsonProperty("flr_nm") String flrNm,
            @JsonProperty("rcept_dt") String rceptDt,
            String rm
    ) {
    }
}
