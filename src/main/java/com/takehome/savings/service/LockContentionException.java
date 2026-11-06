package com.takehome.savings.service;

import java.time.Duration;

/**
 * The per-customer advisory lock could not be acquired within the configured
 * bounded wait ({@code lock_timeout}). Distinct from "database down": Postgres is
 * healthy, this customer's creation lane is merely busy. Mapped to 503
 * {@code contention} with {@code retryable: true}.
 */
public class LockContentionException extends RuntimeException {

    private final String customerName;
    private final Duration waited;

    public LockContentionException(String customerName, Duration waited) {
        super("Customer '%s' is being created concurrently; lock not acquired within %ds"
                .formatted(customerName, waited.toSeconds()));
        this.customerName = customerName;
        this.waited = waited;
    }

    public String getCustomerName() {
        return customerName;
    }

    public Duration getWaited() {
        return waited;
    }
}
