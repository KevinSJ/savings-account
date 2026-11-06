package com.takehome.savings.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.takehome.savings.domain.SavingsAccount;

/**
 * Wire representation of a savings account. Mirrors the domain row minus internals.
 * Absent nickname serialises as field-absent (not {@code null}): optional data should
 * look optional on the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(
        long id,
        String accountNumber,
        String customerName,
        String nickname,
        Instant createdAt) {

    public static AccountResponse from(SavingsAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerName(),
                account.getNickname(),
                account.getCreatedAt());
    }
}
