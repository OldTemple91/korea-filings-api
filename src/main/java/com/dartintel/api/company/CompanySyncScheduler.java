package com.dartintel.api.company;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@code company} table in sync with DART's daily corpCode
 * dump.
 *
 * <p>Two run paths:
 * <ol>
 *   <li>Bootstrap on application start — when the table is empty,
 *       priming a fresh deploy with the full ~2.5k listed-company
 *       directory in one ~10-second sync.</li>
 *   <li>Daily refresh — picks up rebrandings, new IPOs, market
 *       transitions. The DART file regenerates daily; we run at
 *       09:30 KST (DART's typical publish window is overnight).</li>
 * </ol>
 *
 * <p>Disabled via {@code company.sync.enabled=false} for tests and
 * smoke runs that should not hit the DART API.
 */
@Component
@ConditionalOnProperty(name = "company.sync.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CompanySyncScheduler implements ApplicationRunner {

    private final CompanyService service;
    private final CompanyRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        long count = repository.count();
        if (count > 0) {
            log.info("Company directory already populated ({} rows). Skipping bootstrap.", count);
            return;
        }
        log.info("Company directory empty — running bootstrap sync...");
        try {
            int upserted = service.syncDirectory();
            log.info("Bootstrap sync inserted {} listed companies.", upserted);
        } catch (Exception e) {
            log.error("Bootstrap sync failed; the by-ticker endpoint will be unavailable until the next scheduled run. cause={}",
                    e.getMessage(), e);
        }
    }

    /**
     * Daily 09:30 KST refresh. Uses Asia/Seoul explicitly so the
     * schedule does not drift if the JVM default zone changes.
     */
    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Seoul")
    public void refresh() {
        try {
            service.syncDirectory();
        } catch (Exception e) {
            log.warn("Daily company sync failed: {}", e.getMessage(), e);
        }
    }
}
