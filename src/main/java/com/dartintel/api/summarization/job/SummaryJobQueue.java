package com.dartintel.api.summarization.job;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class SummaryJobQueue {

    static final String QUEUE_KEY = "summary_job_queue";

    private final StringRedisTemplate redisTemplate;

    public void push(String rcptNo) {
        redisTemplate.opsForList().leftPush(QUEUE_KEY, rcptNo);
    }

    public String pop(Duration timeout) {
        return redisTemplate.opsForList().rightPop(QUEUE_KEY, timeout);
    }

    public long size() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0L;
    }
}
