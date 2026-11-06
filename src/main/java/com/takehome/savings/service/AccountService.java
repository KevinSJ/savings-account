package com.takehome.savings.service;

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

    private final SavingsAccountRepository repository;
    private final AccountNumberGenerator numberGenerator;

    public AccountService(SavingsAccountRepository repository, AccountNumberGenerator numberGenerator) {
        this.repository = repository;
        this.numberGenerator = numberGenerator;
    }

    @Transactional
    public SavingsAccount create(String customerName, String nickname) {
        String name = requireName(customerName);

        // Lock first, count second - the pair is atomic enough under the lock.
        repository.acquireCustomerAccountLock(name);
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

    private String requireName(String customerName) {
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("customerName is mandatory");
        }
        return customerName.strip();
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
}
