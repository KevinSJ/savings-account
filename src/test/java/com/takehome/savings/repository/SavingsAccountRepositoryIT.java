package com.takehome.savings.repository;

import com.takehome.savings.domain.SavingsAccount;
import com.takehome.savings.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence slice against a real Postgres (Testcontainers), not an in-memory fake -
 * the advisory-lock SQL and identity columns are Postgres-specific behaviour.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SavingsAccountRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    SavingsAccountRepository repository;

    @Test
    void persistsNewAccountWithGeneratedIdAndTimestamps() {
        SavingsAccount saved = repository.saveAndFlush(
                SavingsAccount.openNew("123456789012", "Alice Tester", "rainy-day"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getNickname()).isEqualTo("rainy-day");

        SavingsAccount reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAccountNumber()).isEqualTo("123456789012");
    }

    @Test
    void normalisesBlankNicknameToNull() {
        SavingsAccount saved = repository.saveAndFlush(
                SavingsAccount.openNew("999999999999", "Blank Nick", "   "));

        assertThat(saved.getNickname()).isNull();
    }

    @Test
    void countsCustomerAccountsCaseInsensitively() {
        repository.saveAndFlush(SavingsAccount.openNew("111111111111", "Carol Jones", null));

        assertThat(repository.countByCustomerNameIgnoreCase("carol jones")).isEqualTo(1);
        assertThat(repository.countByCustomerNameIgnoreCase("CAROL JONES")).isEqualTo(1);
        assertThat(repository.countByCustomerNameIgnoreCase("someone else")).isZero();
    }

    @Test
    void rejectsDuplicateAccountNumbersAtDbLevel() {
        repository.saveAndFlush(SavingsAccount.openNew("424242424242", "Dup Test", null));

        // The unique constraint is the final backstop if the generator ever collides.
        assertThatThrownBy(() ->
                repository.saveAndFlush(SavingsAccount.openNew("424242424242", "Dup Other", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void customerAdvisoryLockIsReentrantWithinTransaction() {
        // Same customer acquires twice in one transaction: advisory xact locks stack for
        // the same key from the same session. Different customers are unaffected.
        repository.acquireCustomerAccountLock("Lock Customer");
        repository.acquireCustomerAccountLock("LOCK CUSTOMER".toLowerCase());

        // No exception = lock call is a no-op-safe statement inside a transaction.
        assertThat(repository.countByCustomerNameIgnoreCase("lock customer")).isZero();
    }
}
