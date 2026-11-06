package com.takehome.savings.service;

import java.text.Normalizer;
import java.time.Duration;

import com.takehome.savings.config.CacheConfig;
import com.takehome.savings.domain.SavingsAccount;
import com.takehome.savings.repository.SavingsAccountRepository;

import org.springframework.cache.annotation.Cacheable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for savings accounts.
 *
 * <p>Creation flow (single Postgres transaction):
 * <ol>
 *   <li>take the per-customer advisory lock - serialises concurrent openings,</li>
 *   <li>count the customer's existing accounts - enforce the max-5 rule,</li>
 *   <li>allocate a unique Luhn-valid account number (probe-and-retry),</li>
 *   <li>insert.</li>
 * </ol>
 * Steps 1+2 together make the limit race-free; the unique constraint is the last-resort
 * backstop for step 3.
 */
@Service
public class AccountService {

    /** Hard requirement: a customer may hold at most this many savings accounts. */
    public static final int MAX_ACCOUNTS_PER_CUSTOMER = 5;

    static final int MAX_NUMBER_COLLISION_RETRIES = 5;

    /** Bounded wait for the per-customer advisory lock (must match the repository's set_config). */
    static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(2);

    private final SavingsAccountRepository repository;
    private final AccountNumberGenerator numberGenerator;

    public AccountService(SavingsAccountRepository repository, AccountNumberGenerator numberGenerator) {
        this.repository = repository;
        this.numberGenerator = numberGenerator;
    }

    @Transactional
    public SavingsAccount create(String customerName, String nickname) {
        String name = canonicalizeName(customerName);

        // Lock first, count second - the pair is atomic enough under the lock.
        // The lock key is the canonical name: whitespace variants and unicode
        // equivalents collapse to one customer (review finding #2). The wait is
        // bounded (2s) so extreme contention becomes an honest 503 instead of an
        // unbounded pile-up (review finding #4).
        acquireLockUnderTimeout(name);
        long held = repository.countByCustomerNameIgnoreCase(name);
        if (held >= MAX_ACCOUNTS_PER_CUSTOMER) {
            throw new AccountLimitExceededException(name, MAX_ACCOUNTS_PER_CUSTOMER);
        }

        String accountNumber = allocateAccountNumber();
        return repository.save(SavingsAccount.openNew(accountNumber, name, nickname));
    }

    /**
     * Fetch one account by surrogate id.
     *
     * <p>Cached in Redis ({@link com.takehome.savings.config.CacheConfig}): misses hit
     * Postgres, hits are served from Redis; a Redis outage degrades to Postgres reads
     * via the configured {@code CacheErrorHandler}. Not-found ids are never cached -
     * they stay cheap rejects.
     */
    @Cacheable(cacheNames = CacheConfig.ACCOUNTS_CACHE)
    @Transactional(readOnly = true)
    public SavingsAccount getById(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Fetch one account by its public account number.
     *
     * <p>Review finding #1: gives integrators a random-looking public lookup key so
     * callers do not have to lean on the sequential surrogate id in URLs.
     * Malformed numbers are rejected before touching the datastore.
     */
    @Cacheable(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "'number:' + #accountNumber")
    @Transactional(readOnly = true)
    public SavingsAccount getByAccountNumber(String accountNumber) {
        if (accountNumber == null
                || !LuhnAccountNumberGenerator.isLuhnValid(accountNumber)) {
            throw new IllegalArgumentException("accountNumber is not a valid number");
        }
        return repository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> AccountNotFoundException.forAccountNumber(accountNumber));
    }

    private String canonicalizeName(String customerName) {
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("customerName is mandatory");
        }
        // Canonical form: unicode-equivalent spellings fold to the same string
        // (NFKC), internal whitespace runs collapse, edges trimmed. "Alice
        //  Tester", "Alice\tTester" and fullwidth "Ａｌｉｃｅ Ｔｅｓｔｅｒ" all
        // become one identity. Case is preserved for display; case-insensitive
        // matching happens in SQL (upper()), whose functional index serves it.
        String canonical = Normalizer.normalize(customerName, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip();
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("customerName is mandatory");
        }
        return canonical;
    }

    /**
     * Generate candidates until an unused one appears. Expected attempts ~ 1; the cap
     * converts a pathological collision streak into an explicit server error rather
     * than an unbounded loop.
     */
    private String allocateAccountNumber() {
        for (int attempt = 0; attempt < MAX_NUMBER_COLLISION_RETRIES; attempt++) {
            String candidate = numberGenerator.generate();
            if (!repository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new AccountNumberAllocationException(MAX_NUMBER_COLLISION_RETRIES);
    }

    /**
     * Takes the per-customer advisory lock under a bounded wait and distinguishes
     * "busy lane" from "broken datastore".
     *
     * <p>Postgres signals an expired {@code lock_timeout} with SQLState 55P03,
     * which Spring's default SQL error code translation does not recognise for
     * PostgreSQL - it surfaces as a wrapped persistence exception rather than
     * {@code CannotAcquireLockException}. The cause chain is therefore inspected
     * explicitly so contention and outage keep different problem types.
     */
    private void acquireLockUnderTimeout(String name) {
        repository.boundAdvisoryLockWait();
        try {
            repository.acquireCustomerAccountLock(name);
        } catch (RuntimeException ex) {
            if (isLockTimeout(ex)) {
                throw new LockContentionException(name, LOCK_WAIT_TIMEOUT);
            }
            throw ex;
        }
    }

    private static boolean isLockTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sqlException
                    && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
