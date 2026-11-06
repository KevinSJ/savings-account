package com.takehome.savings.it;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datastore-unavailable behaviour, verified over real HTTP.
 *
 * <p>This context deliberately points at ports nobody listens on (Postgres on :1,
 * Redis on an unassigned high port). The app must still BOOT - partial failure is a
 * runtime condition, not a boot blocker - and degrade every request into a retryable
 * 503 problem document.
 *
 * <p>Standalone on purpose: the shared integration base registers container
 * properties via {@code @DynamicPropertySource}, which outranks test-class properties;
 * isolating this context keeps the dead endpoints authoritative.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/savings",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=59999"
})
class ResilienceDbDownIT {

    @Autowired
    TestRestTemplate http;

    @Test
    void createFailsWithRetryable503() {
        ResponseEntity<String> response = http.postForEntity("/api/v1/accounts",
                Map.of("customerName", "Offline Olga"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).contains("datastore-unavailable");
        assertThat(response.getBody()).contains("\"retryable\":true");
    }

    @Test
    void fetchFailsWithRetryable503() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/accounts/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("datastore-unavailable");
    }
}
