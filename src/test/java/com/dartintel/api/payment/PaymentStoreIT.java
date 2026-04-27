package com.dartintel.api.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PaymentStoreIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private PaymentStore store;

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
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        X402Properties props = new X402Properties(
                "http://x", "eip155:84532", "0x", "0x", 300,
                new X402Properties.Timeout(1000, 1000),
                new X402Properties.Replay(3600),
                new X402Properties.Cdp("", "")
        );
        store = new PaymentStore(redisTemplate, props);
    }

    @Test
    void firstRegistrationReturnsTrueSecondReturnsFalse() {
        String hash = "a".repeat(64);

        assertThat(store.registerIfAbsent(hash)).isTrue();
        assertThat(store.registerIfAbsent(hash)).isFalse();
    }

    @Test
    void releaseAllowsTheSameSignatureToRegisterAgain() {
        String hash = "b".repeat(64);
        assertThat(store.registerIfAbsent(hash)).isTrue();

        store.release(hash);

        assertThat(store.registerIfAbsent(hash)).isTrue();
    }

    @Test
    void distinctHashesAreIndependent() {
        String hash1 = "c".repeat(64);
        String hash2 = "d".repeat(64);

        assertThat(store.registerIfAbsent(hash1)).isTrue();
        assertThat(store.registerIfAbsent(hash2)).isTrue();
        assertThat(store.registerIfAbsent(hash1)).isFalse();
        assertThat(store.registerIfAbsent(hash2)).isFalse();
    }
}
