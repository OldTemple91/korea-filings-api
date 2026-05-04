package com.dartintel.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Replace Spring Boot's default single-thread scheduler with a
 * pooled one so {@code @Scheduled} methods cannot starve each other.
 *
 * <p>The default {@link ThreadPoolTaskScheduler} pool size is 1, which
 * means a long-running scheduled task pins every other scheduled task
 * for the duration. This service has at least three concurrent
 * cadences:
 *
 * <ul>
 *   <li>{@code DartPollingScheduler.poll} — every 30 s (DART list fetch + DB writes).</li>
 *   <li>{@code SummaryRetryScheduler.retryOrphanedSummaries} — every minute (Postgres scan + Redis enqueue).</li>
 *   <li>{@code CompanySyncScheduler.bootstrapIfEmpty} + daily refresh
 *       at 09:30 KST — pulls the {@code corpCode.xml} dump, which
 *       can take multiple minutes.</li>
 * </ul>
 *
 * If all three share a single thread, a slow corpCode download
 * silently delays every other cadence past its fire interval. A pool
 * of 3 gives each one its own thread without over-provisioning.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.initialize();
        return scheduler;
    }
}
