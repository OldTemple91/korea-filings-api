package com.dartintel.api.summarization;

import java.time.LocalDate;

public record DisclosureContext(
        String rcptNo,
        String corpCode,
        String corpName,
        String corpNameEng,
        String reportNm,
        LocalDate rceptDt,
        String rm
) {
}
