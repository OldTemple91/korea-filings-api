package com.dartintel.api.company;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public-facing shape of a company record. Flattens the entity and
 * drops operational fields ({@code synced_at}, {@code last_modified_at})
 * that are useful for ops but noise for an API consumer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDto(
        String ticker,
        String corpCode,
        String nameKr,
        String nameEn,
        String market
) {

    public static CompanyDto from(Company company) {
        return new CompanyDto(
                company.getTicker(),
                company.getCorpCode(),
                company.getNameKr(),
                company.getNameEn(),
                company.getMarket()
        );
    }
}
