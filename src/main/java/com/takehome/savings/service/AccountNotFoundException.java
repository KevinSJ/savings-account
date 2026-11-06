package com.takehome.savings.service;

/** No savings account exists for the requested id. Mapped to HTTP 404. */
public class AccountNotFoundException extends RuntimeException {

    private final long accountId;

    public AccountNotFoundException(long accountId) {
        super("Savings account %d does not exist".formatted(accountId));
        this.accountId = accountId;
    }

    /** Factory for lookups by account number (the id stays unset). */
    public static AccountNotFoundException forAccountNumber(String accountNumber) {
        return new AccountNotFoundException(-1) {
            @Override
            public String getMessage() {
                return "Savings account %s does not exist".formatted(accountNumber);
            }
        };
    }

    public long getAccountId() {
        return accountId;
    }
}
