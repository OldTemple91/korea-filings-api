package com.dartintel.api.summarization.job;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SummaryJobQueueIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SummaryJobQueue queue;

    @BeforeAll
    static void startFactory() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void stopFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.delete(SummaryJobQueue.QUEUE_KEY);
        queue = new SummaryJobQueue(redisTemplate);
    }

    @Test
    void pushThenPopReturnsTheSameRcptNo() {
        queue.push("20260424000001");

        String popped = queue.pop(Duration.ofSeconds(2));

        assertThat(popped).isEqualTo("20260424000001");
    }

    @Test
    void multiplePushesPopInFifoOrder() {
        queue.push("A");
        queue.push("B");
        queue.push("C");

        assertThat(queue.pop(Duration.ofSeconds(2))).isEqualTo("A");
        assertThat(queue.pop(Duration.ofSeconds(2))).isEqualTo("B");
        assertThat(queue.pop(Duration.ofSeconds(2))).isEqualTo("C");
    }

    @Test
    void popOnEmptyQueueReturnsNullAfterTimeout() {
        String popped = queue.pop(Duration.ofMillis(500));

        assertThat(popped).isNull();
    }

    @Test
    void sizeReflectsPendingItems() {
        assertThat(queue.size()).isZero();

        queue.push("A");
        queue.push("B");

        assertThat(queue.size()).isEqualTo(2);

        queue.pop(Duration.ofSeconds(2));

        assertThat(queue.size()).isEqualTo(1);
    }
}
