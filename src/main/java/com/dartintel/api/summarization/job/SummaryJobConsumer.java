package com.dartintel.api.summarization.job;

import com.dartintel.api.summarization.SummaryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "summary.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class SummaryJobConsumer {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);

    private final SummaryJobQueue queue;
    private final SummaryService summaryService;

    private volatile boolean running;
    private Thread worker;

    @PostConstruct
    void start() {
        running = true;
        worker = new Thread(this::loop, "summary-job-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("SummaryJobConsumer started");
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("SummaryJobConsumer stopping");
    }

    void loop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                processOne();
            } catch (Exception e) {
                log.error("Consumer loop error: {}", e.getMessage(), e);
                sleepQuietly(ERROR_BACKOFF);
            }
        }
        log.info("SummaryJobConsumer loop exited");
    }

    void processOne() {
        String rcptNo = queue.pop(POLL_TIMEOUT);
        if (rcptNo == null) {
            return;
        }
        try {
            summaryService.summarize(rcptNo);
        } catch (Exception e) {
            log.error("Failed to summarize rcpt_no={}: {}", rcptNo, e.getMessage(), e);
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
