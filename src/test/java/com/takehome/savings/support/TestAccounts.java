package com.takehome.savings.support;

import java.time.Instant;
import java.util.UUID;

import com.takehome.savings.domain.SavingsAccount;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds SavingsAccount fixtures that look "already persisted": id, timestamps and
 * optimistic-lock version are assigned by Hibernate in production, and tests outside
 * the persistence layer frequently need those fields populated.
 */
public final class TestAccounts {

    public static final Instant DEFAULT_CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");

    private TestAccounts() {
    }

    public static SavingsAccount persisted(long internalId, UUID accountId, String accountNumber,
                                           String customerName, String nickname) {
        return persisted(internalId, accountId, accountNumber, customerName, nickname, DEFAULT_CREATED_AT);
    }

    public static SavingsAccount persisted(long internalId, UUID accountId, String accountNumber,
                                           String customerName, String nickname, Instant createdAt) {
        SavingsAccount account = SavingsAccount.openNew(accountNumber, customerName, nickname);
        ReflectionTestUtils.setField(account, "id", internalId);
        ReflectionTestUtils.setField(account, "accountUid", accountId);
        ReflectionTestUtils.setField(account, "createdAt", createdAt);
        return account;
    }
}
