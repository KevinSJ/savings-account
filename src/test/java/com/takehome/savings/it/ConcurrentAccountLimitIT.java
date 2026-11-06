package com.takehome.savings.it;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.takehome.savings.repository.SavingsAccountRepository;
import com.takehome.savings.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the max-5 rule under genuine concurrent creation.
 *
 * <p>Without the per-customer advisory lock, eight parallel POSTs against a
 * four-account customer would happily produce the forbidden fifth AND sixth rows -
 * the SELECT count / INSERT pair is not atomic under READ COMMITTED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentAccountLimitIT extends AbstractIntegrationTest {

    private static final String CUSTOMER = "Race Rita";
    private static final int WORKERS = 8;

    @Autowired
    TestRestTemplate http;

    @Autowired
    SavingsAccountRepository repository;

    @Test
    void concurrentCreationsNeverExceedFivePerCustomer() throws Exception {
        // Seed the customer at the edge of the quota.
        for (int i = 1; i <= 4; i++) {
            ResponseEntity<Map> seed = http.postForEntity("/api/v1/accounts",
                    Map.of("customerName", CUSTOMER, "nickname", "starter " + i), Map.class);
            assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                // Mixed-case variants race for the same quota: lock key and count must
                // agree on customer identity.
                String variant = (i % 2 == 0) ? CUSTOMER : CUSTOMER.toUpperCase(java.util.Locale.ROOT);
                Callable<Integer> worker = () -> {
                    start.await();
                    ResponseEntity<Map> response = http.postForEntity("/api/v1/accounts",
                            Map.of("customerName", variant, "nickname", "race entry"), Map.class);
                    return response.getStatusCode().value();
                };
                futures.add(pool.submit(worker));
            }
            start.countDown();

            var statuses = new ConcurrentLinkedQueue<Integer>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(Duration.ofSeconds(55).toSeconds(), TimeUnit.SECONDS));
            }

            // Every worker got a definitive answer, and the quota held: at most one
            // extra account beyond the four seeds.
            assertThat(statuses).hasSize(WORKERS);
            long created = statuses.stream().filter(s -> s == 201).count();
            long rejected = statuses.stream().filter(s -> s == 409).count();
            assertThat(created).isLessThanOrEqualTo(1);
            assertThat(created + rejected).isEqualTo(WORKERS);

            assertThat(repository.countByCustomerNameIgnoreCase(CUSTOMER)).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }
}
