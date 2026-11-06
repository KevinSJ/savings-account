package com.takehome.savings.repository;

import com.takehome.savings.domain.SavingsAccount;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavingsAccountRepository extends JpaRepository<SavingsAccount, Long> {

    /**
     * How many accounts the customer already holds. Case-insensitive on purpose:
     * two spellings of the same name are treated as one customer (see trade-offs doc).
     */
    long countByCustomerNameIgnoreCase(String customerName);

    /** Collision probe backing the random account-number generator. */
    boolean existsByAccountNumber(String accountNumber);

    /**
     * Serialises account creation per customer across all application instances.
     *
     * <p>A plain {@code count(*)}-then-insert races under READ COMMITTED: two concurrent
     * requests both observe 4 existing accounts and both insert, producing the 6th account.
     * A transaction-scoped Postgres advisory lock (keyed on the customer name) closes that
     * race while remaining deadlock-free (single lock, always acquired before any read).
     *
     * <p>The key folds the name through {@code lower()} to match the case-insensitive
     * count semantics above - otherwise "Alice" and "ALICE" would take different locks
     * while racing against the same quota.
     *
     * <p>Alternatives considered (serialisable isolation + retry, customer-side rows to
     * pessimistically lock, DB triggers) are compared in docs/DESIGN-TRADEOFFS.md.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(lower(:customerName), 0))",
            nativeQuery = true)
    void acquireCustomerAccountLock(@Param("customerName") String customerName);
}
