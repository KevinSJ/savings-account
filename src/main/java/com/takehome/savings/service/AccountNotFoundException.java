package com.takehome.savings.service;

/** No savings account exists for the requested id. Mapped to HTTP 404. */
public class AccountNotFoundException extends RuntimeException {

    private final long accountId;

    public AccountNotFoundException(long accountId) {
        super("Savings account %d does not exist".formatted(accountId));
        this.accountId = accountId;
    }

    public long getAccountId() {
        return accountId;
    }
}
