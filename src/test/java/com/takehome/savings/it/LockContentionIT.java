package com.takehome.savings.it;

import java.sql.Connection;
import java.time.Duration;

import javax.sql.DataSource;

import com.takehome.savings.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding #4, end to end: a pre-existing holder of the per-customer
 * advisory lock must turn new creates into a BOUNDED, retryable 503 - not an
 * indefinite hang. Before the lock_timeout fix this request blocked forever.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LockContentionIT extends AbstractIntegrationTest {

    private static final String CUSTOMER = "Patient Paula";

    @Autowired
    TestRestTemplate http;

    @Autowired
    DataSource dataSource;

    @Test
    void heldAdvisoryLockYieldsTimelyRetryable503() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            // Take the exact lock key the service would take for this customer,
            // from an unrelated session, and keep it held across the request.
            try (var statement = conn.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(upper(?), 0))")) {
                statement.setString(1, CUSTOMER);
                statement.executeQuery().close();
            }

            long started = System.nanoTime();
            ResponseEntity<String> response = http.postForEntity("/api/v1/accounts",
                    java.util.Map.of("customerName", CUSTOMER, "nickname", "queued entry"),
                    String.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).contains("contention");
            // Bounded by lock_timeout=2s plus HTTP slop; well short of a hang.
            assertThat(elapsed).isGreaterThan(Duration.ofMillis(1500));
            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));

            // The connection is still usable and un-poisoned: release, and the
            // very next create succeeds once the lock is free.
            conn.rollback();
        }

        ResponseEntity<String> afterRelease = http.postForEntity("/api/v1/accounts",
                java.util.Map.of("customerName", CUSTOMER, "nickname", "patient second"),
                String.class);
        assertThat(afterRelease.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
