package com.takehome.savings.service;

/** A customer tried to exceed their quota of savings accounts. Mapped to HTTP 409. */
public class AccountLimitExceededException extends RuntimeException {

    private final String customerName;
    private final int maxAccounts;

    public AccountLimitExceededException(String customerName, int maxAccounts) {
        super("Customer '%s' already holds the maximum of %d savings accounts"
                .formatted(customerName, maxAccounts));
        this.customerName = customerName;
        this.maxAccounts = maxAccounts;
    }

    public String getCustomerName() {
        return customerName;
    }

    public int getMaxAccounts() {
        return maxAccounts;
    }
}
