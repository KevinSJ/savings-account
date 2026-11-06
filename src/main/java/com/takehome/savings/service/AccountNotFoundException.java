package com.takehome.savings.service;

/**
 * No savings account matches the requested identifier. Mapped to HTTP 404.
 * The identifier may be an external account UID (UUID) or an account number;
 * both are opaque strings for message purposes.
 */
public class AccountNotFoundException extends RuntimeException {

    private final String identifier;

    public AccountNotFoundException(String identifier) {
        super("Savings account %s does not exist".formatted(identifier));
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }
}
