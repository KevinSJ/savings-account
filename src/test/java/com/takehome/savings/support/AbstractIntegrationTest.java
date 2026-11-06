package com.takehome.savings.support;

import java.util.stream.Stream;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.lifecycle.Startables;

/**
 * Singleton-container base for every integration test in this suite.
 *
 * <p>Containers are started once per JVM (static initialiser, Testcontainers'
 * "singleton container" pattern) and reused by all test classes, which keeps the
 * integration suites to a couple of container boots for the whole build.
 *
 * <p>Deliberate choice over {@code @ServiceConnection}: that maps a container to each
 * test class lifecycle, whereas here several specialised contexts (dead-Postgres,
 * dead-Redis) must coexist with the happy-path context and share the same live
 * containers. Wiring is explicit below instead of implicit per annotation.
 */
public abstract class AbstractIntegrationTest {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    static {
        // Start in parallel, fail the whole suite fast if Docker is unavailable.
        Startables.deepStart(Stream.of(POSTGRES, REDIS)).join();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }
}
