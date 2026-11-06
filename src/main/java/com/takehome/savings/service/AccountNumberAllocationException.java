package com.takehome.savings.service;

/**
 * The generator could not produce an unused account number within the retry budget.
 * Practically a 1-in-10^11 event per attempt; mapped to HTTP 503 so callers retry,
 * and an operator alarm would investigate why uniqueness probing is failing.
 */
public class AccountNumberAllocationException extends RuntimeException {

    private final int attempts;

    public AccountNumberAllocationException(int attempts) {
        super("Could not allocate a unique account number after %d attempts".formatted(attempts));
        this.attempts = attempts;
    }

    public int getAttempts() {
        return attempts;
    }
}
