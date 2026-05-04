package com.dartintel.api.summarization.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Backed by a Redis list (LPUSH / BRPOP). Bounded with an LTRIM cap
 * so a stuck consumer cannot grow the queue without limit and
 * eventually OOM Redis — the SummaryRetryScheduler walks Postgres
 * for missing summaries on a separate cadence, so dropping the
 * oldest queued items in an outage is recoverable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SummaryJobQueue {

    static final String QUEUE_KEY = "summary_job_queue";

    /**
     * Soft cap on queue depth. Postgres-backed retry scheduler
     * rebuilds the queue from {@code findRcptNosMissingSummary}
     * every 5 min, so an LTRIM that drops the oldest entries during
     * a consumer outage is safe — no data is lost, just delayed.
     */
    private static final int MAX_QUEUE_DEPTH = 10_000;

    private final StringRedisTemplate redisTemplate;

    public void push(String rcptNo) {
        redisTemplate.opsForList().leftPush(QUEUE_KEY, rcptNo);
        // After every push, cap the queue so a stuck consumer plus a
        // burst of new filings cannot exceed the cap. LTRIM keeps the
        // most recent N (the head, since we LPUSH and BRPOP from the
        // tail). Older items are dropped — the retry scheduler will
        // re-enqueue them on the next cycle if they still need work.
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        if (size != null && size > MAX_QUEUE_DEPTH) {
            redisTemplate.opsForList().trim(QUEUE_KEY, 0, MAX_QUEUE_DEPTH - 1);
            if (size > MAX_QUEUE_DEPTH * 2L) {
                log.warn("summary_job_queue depth {} > 2× cap; consumer may be stuck", size);
            }
        }
    }

    public String pop(Duration timeout) {
        return redisTemplate.opsForList().rightPop(QUEUE_KEY, timeout);
    }

    public long size() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0L;
    }
}
