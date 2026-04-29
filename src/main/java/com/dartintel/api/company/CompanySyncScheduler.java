package com.dartintel.api.company;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Keeps the {@code company} table in sync with DART's daily corpCode
 * dump.
 *
 * <p>Two run paths:
 * <ol>
 *   <li>Bootstrap on first start — a one-time ~5–10 minute fetch that
 *       primes a fresh deploy with the full ~2.5k listed-company
 *       directory. Runs 30 seconds after the app becomes ready so it
 *       does <em>not</em> block startup; the by-ticker endpoint
 *       returns empty results during that window, which is acceptable
 *       on a brand-new deploy.</li>
 *   <li>Daily refresh at 09:30 KST — picks up rebrandings, new IPOs,
 *       market transitions. The DART file regenerates daily; we run
 *       after their typical overnight publish window.</li>
 * </ol>
 *
 * <p>Disabled via {@code company.sync.enabled=false} for tests and
 * smoke runs that should not hit the DART API.
 */
@Component
@ConditionalOnProperty(name = "company.sync.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CompanySyncScheduler {

    private final CompanyService service;
    private final CompanyRepository repository;

    /**
     * Bootstrap on a fresh deploy. {@code initialDelay} runs the first
     * tick on a Spring TaskScheduler thread 30 seconds after bean
     * initialisation, so the slow corpCode download does not delay
     * the application becoming Ready. {@code fixedDelay = MAX_VALUE}
     * effectively disables further ticks — the daily refresh below
     * owns the recurring path.
     */
    @Scheduled(initialDelay = 30, fixedDelay = Long.MAX_VALUE, timeUnit = TimeUnit.SECONDS)
    public void bootstrapIfEmpty() {
        long count = repository.count();
        if (count > 0) {
            log.info("Company directory already populated ({} rows). Skipping bootstrap.", count);
            return;
        }
        log.info("Company directory empty — running bootstrap sync (~5-10 min)...");
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
