package com.ticketsystem.schema;

import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Integration base for Day 5 behavior that requires shared Redis state as well as PostgreSQL. */
public abstract class AbstractPostgresRedisIT extends AbstractPostgresIT {

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeAll
    static void checkRedisIsAvailable() {
        if (!REDIS.isRunning()) {
            throw new IllegalStateException("Redis test container failed to start");
        }
    }

    @BeforeEach
    void clearRedis() {
        try (RedisConnection connection = Objects.requireNonNull(redis.getConnectionFactory())
                .getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
