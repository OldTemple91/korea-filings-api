package com.dartintel.api.company;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public-facing shape of a company record. Flattens the entity and
 * drops operational fields ({@code synced_at}, {@code last_modified_at})
 * that are useful for ops but noise for an API consumer.
 *
 * <p>{@code isExactMatch} signals whether the row's ticker, Korean
 * name, or English name equals the query string (case-insensitive,
 * trim-aware). Trigram fuzzy search returns up to 50 hits in
 * relevance order, and the leading hit is usually but not always
 * the canonical answer. An agent searching {@code "samsung"} can
 * see eight Samsung-* hits with no automatic way to pick the right
 * one; the {@code isExactMatch} flag lets the agent prefer an
 * unambiguous result. Null for the {@code /v1/companies/{ticker}}
 * direct-lookup path where the question doesn't apply.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDto(
        String ticker,
        String corpCode,
        String nameKr,
        String nameEn,
        String market,
        Boolean isExactMatch
) {

    public static CompanyDto from(Company company) {
        return new CompanyDto(
                company.getTicker(),
                company.getCorpCode(),
                company.getNameKr(),
                company.getNameEn(),
                company.getMarket(),
                null
        );
    }

    public static CompanyDto fromSearchHit(Company company, String query) {
        return new CompanyDto(
                company.getTicker(),
                company.getCorpCode(),
                company.getNameKr(),
                company.getNameEn(),
                company.getMarket(),
                isExactMatch(company, query)
        );
    }

    private static boolean isExactMatch(Company c, String query) {
        if (query == null) return false;
        String q = query.strip();
        if (q.isEmpty()) return false;
        return q.equalsIgnoreCase(c.getTicker())
                || q.equalsIgnoreCase(c.getNameKr())
                || q.equalsIgnoreCase(c.getNameEn());
    }
}
