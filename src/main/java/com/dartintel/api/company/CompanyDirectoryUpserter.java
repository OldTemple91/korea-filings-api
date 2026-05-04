package com.dartintel.api.company;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a parsed {@code corpCode.xml} batch to the {@code company}
 * table in a single transaction. Lives in its own bean so the
 * {@link Transactional} annotation actually takes effect — Spring
 * AOP only intercepts cross-bean calls, so embedding the upsert in
 * {@link CompanyService} would have made the annotation a no-op
 * (self-invocation bypasses the proxy).
 *
 * <p>The split also keeps the HTTP fetch + XML parse in
 * {@link CompanyService#syncDirectory} OUTSIDE any transaction —
 * a multi-minute corpCode download must not pin a Hikari connection.
 */
@Component
@RequiredArgsConstructor
class CompanyDirectoryUpserter {

    private final CompanyRepository repository;

    @Transactional
    public int upsert(List<CompanyService.Row> rows) {
        Map<String, Company> existing = new HashMap<>();
        repository.findAll().forEach(c -> existing.put(c.getTicker(), c));

        int upserted = 0;
        for (CompanyService.Row r : rows) {
            if (r.ticker() == null) {
                continue;
            }
            Company company = existing.get(r.ticker());
            if (company == null) {
                repository.save(new Company(
                        r.ticker(), r.corpCode(), r.nameKr(), r.nameEn(),
                        CompanyService.guessMarket(r.ticker()), r.modifyDate()));
            } else {
                company.update(r.nameKr(), r.nameEn(),
                        CompanyService.guessMarket(r.ticker()), r.modifyDate());
            }
            upserted++;
        }
        return upserted;
    }
}
