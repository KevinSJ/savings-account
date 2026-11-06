package com.takehome.savings.it;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache-outage behaviour: Redis is unreachable while Postgres is healthy.
 * Reads must silently degrade to the database - the cache is an optimisation,
 * never a dependency. Writes are untouched by the cache and simply work.
 *
 * <p>Runs in its own context: the shared base registers container properties via
 * {@code @DynamicPropertySource}, which would outrank any test-class properties
 * (test-properties lose to dynamic sources), so this class owns its Postgres.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Nothing listens on this port: every cache interaction fails.
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=59999"
})
class ResilienceCacheDownIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        Startables.deepStart(Stream.of(POSTGRES)).join();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void writeWorksWithCacheDown() {
        ResponseEntity<Map> created = http.postForEntity("/api/v1/accounts",
                Map.of("customerName", "Cache Out Chloe", "nickname", "no redis"),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // the id is only in the Location header now
        String locationPath = created.getHeaders().getLocation().getPath();

        // First GET: cache lookup fails (swallowed), cache put fails (swallowed),
        // Postgres answers.
        ResponseEntity<Map> first = http.getForEntity(locationPath, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("customerName", "Cache Out Chloe");

        // Second GET: still fine - degradation is stable, not one-shot luck.
        ResponseEntity<Map> second = http.getForEntity(locationPath, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }
}
