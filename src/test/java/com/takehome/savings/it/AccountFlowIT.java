package com.takehome.savings.it;

import java.util.Map;

import com.takehome.savings.support.AbstractIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end flow over a real HTTP port, a real Postgres and a real Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountFlowIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("create -> fetch round-trips through HTTP, Postgres and the Redis cache")
    void createThenFetch() {
        Map<String, Object> request = Map.of("customerName", "Flow Frieda", "nickname", "nest egg");
        ResponseEntity<Map> created = http.postForEntity("/api/v1/accounts", request, Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getBody()).containsEntry("customerName", "Flow Frieda");
        Number id = (Number) created.getBody().get("id");

        // First GET: cache miss, Postgres read, cache write.
        ResponseEntity<Map> first = http.getForEntity("/api/v1/accounts/" + id, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("accountNumber", created.getBody().get("accountNumber"));
        assertThat(first.getBody()).containsEntry("nickname", "nest egg");

        // The cache key materialises synchronously within request handling.
        assertThat(redis.keys("savings:accounts:*")).isNotEmpty();

        // Second GET: served from Redis (same payload).
        ResponseEntity<Map> second = http.getForEntity("/api/v1/accounts/" + id, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("fetching an unknown id yields a 404 problem document")
    void fetchUnknownId() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/accounts/999999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("account-not-found");
    }

    @Test
    @DisplayName("the fifth account succeeds and the sixth is refused with 409")
    void fiveAccountQuotaEnforcedSequentially() {
        String customer = "Quota Quincy";
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> request = Map.of(
                    "customerName", customer,
                    "nickname", "quota no " + i);
            ResponseEntity<Map> response = http.postForEntity("/api/v1/accounts", request, Map.class);
            assertThat(response.getStatusCode())
                    .as("account #%d for %s", i, customer)
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> sixth = http.postForEntity("/api/v1/accounts",
                Map.of("customerName", customer, "nickname", "one too many"),
                String.class);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sixth.getBody()).contains("account-limit-reached");
    }
}
