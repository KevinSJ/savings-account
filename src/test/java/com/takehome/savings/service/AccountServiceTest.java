package com.takehome.savings.service;

import java.util.Optional;

import com.takehome.savings.domain.SavingsAccount;
import com.takehome.savings.repository.SavingsAccountRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private SavingsAccountRepository repository;
    private AccountNumberGenerator generator;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(SavingsAccountRepository.class);
        generator = mock(AccountNumberGenerator.class);
        service = new AccountService(repository, generator);
    }

    @Test
    @DisplayName("create: locks customer, counts, allocates number, saves trimmed data")
    void createHappyPath() {
        when(generator.generate()).thenReturn("123456789012");
        when(repository.countByCustomerNameIgnoreCase(anyString())).thenReturn(0L);
        when(repository.existsByAccountNumber(anyString())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SavingsAccount saved = service.create("  Alice Tester  ", "rainy-day");

        InOrder order = Mockito.inOrder(repository);
        order.verify(repository).boundAdvisoryLockWait();
        order.verify(repository).acquireCustomerAccountLock("Alice Tester");
        order.verify(repository).countByCustomerNameIgnoreCase("Alice Tester");
        order.verify(repository).existsByAccountNumber("123456789012");
        order.verify(repository).save(any());

        assertThat(saved.getAccountNumber()).isEqualTo("123456789012");
        assertThat(saved.getCustomerName()).isEqualTo("Alice Tester");
        assertThat(saved.getNickname()).isEqualTo("rainy-day");
    }

    @Test
    @DisplayName("create: blank nickname collapses to null")
    void createNormalisesBlankNickname() {
        when(generator.generate()).thenReturn("123456789012");
        when(repository.countByCustomerNameIgnoreCase(anyString())).thenReturn(0L);
        when(repository.existsByAccountNumber(anyString())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("Alice", "   ").getNickname()).isNull();
        assertThat(service.create("Alice", null).getNickname()).isNull();
    }

    @Test
    @DisplayName("5th account is allowed, 6th is refused")
    void enforcesMaxFiveAccounts() {
        when(repository.countByCustomerNameIgnoreCase(anyString()))
                .thenReturn(4L)
                .thenReturn(5L);
        when(generator.generate()).thenReturn("123456789012");
        when(repository.existsByAccountNumber(anyString())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("Alice", null)).isNotNull();

        assertThatThrownBy(() -> service.create("Alice", null))
                .isInstanceOfSatisfying(AccountLimitExceededException.class,
                        ex -> assertThat(ex.getMaxAccounts()).isEqualTo(5));
        // Exactly one save happened (the legal 5th); the 6th attempt allocated nothing.
        verify(repository, times(1)).save(any());
        verify(generator, times(1)).generate();
    }

    @Test
    @DisplayName("create: retries when a generated number already exists")
    void retriesOnAccountNumberCollision() {
        when(generator.generate()).thenReturn("100000000001", "100000000002", "100000000003");
        when(repository.countByCustomerNameIgnoreCase(anyString())).thenReturn(0L);
        when(repository.existsByAccountNumber(anyString()))
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SavingsAccount saved = service.create("Bob", null);

        assertThat(saved.getAccountNumber()).isEqualTo("100000000003");
        verify(generator, times(3)).generate();
    }

    @Test
    @DisplayName("create: exhausted retries escalate into an explicit exception")
    void allocationExhaustionThrows() {
        when(generator.generate()).thenReturn("1");
        when(repository.countByCustomerNameIgnoreCase(anyString())).thenReturn(0L);
        when(repository.existsByAccountNumber(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create("Bob", null))
                .isInstanceOf(AccountNumberAllocationException.class);
        verify(generator, times(AccountService.MAX_NUMBER_COLLISION_RETRIES)).generate();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create: 55P03 lock timeout surfaces as LockContentionException, not outage")
    void lockTimeoutIsClassifiedAsContention() throws java.sql.SQLException {
        when(repository.countByCustomerNameIgnoreCase(anyString())).thenReturn(0L);
        Mockito.doThrow(new RuntimeException(
                // Postgres signals an expired lock_timeout with SQLState 55P03.
                new java.sql.SQLException("canceling statement due to lock timeout", "55P03", 0)))
                .when(repository).acquireCustomerAccountLock(anyString());

        assertThatThrownBy(() -> service.create("Busy Bella", null))
                .isInstanceOfSatisfying(LockContentionException.class,
                        ex -> assertThat(ex.getWaited()).hasSeconds(2));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create: non-timeout persistence errors keep their original type")
    void otherDatastoreErrorsAreNotRelabelled() {
        when(repository.countByCustomerNameIgnoreCase(anyString())).thenReturn(0L);
        Mockito.doThrow(new DataAccessResourceFailureException("down"))
                .when(repository).acquireCustomerAccountLock(anyString());

        assertThatThrownBy(() -> service.create("Bella", null))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("create: blank customer name is rejected before touching the database")
    void blankNameRejected() {
        assertThatThrownBy(() -> service.create("   ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).acquireCustomerAccountLock(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("getByAccountNumber: malformed number rejected without a datastore hit")
    void getByAccountNumberRejectsMalformed() {
        assertThatThrownBy(() -> service.getByAccountNumber("325748979051"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findByAccountNumber(anyString());
    }

    @Test
    @DisplayName("getByAccountNumber: valid number found/missing branches")
    void getByAccountNumberBranches() {
        java.util.Optional<SavingsAccount> entity =
                java.util.Optional.of(SavingsAccount.openNew("325748979052", "Alice", null));
        when(repository.findByAccountNumber("325748979052")).thenReturn(entity);
        when(repository.findByAccountNumber("629054787375")).thenReturn(java.util.Optional.empty());

        assertThat(service.getByAccountNumber("325748979052")).isSameAs(entity.get());
        assertThatThrownBy(() -> service.getByAccountNumber("629054787375"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Savings account 629054787375 does not exist");
    }

    @Test
    @DisplayName("getById: found and missing branches")
    void getById() {
        SavingsAccount entity = SavingsAccount.openNew("123456789012", "Alice", null);
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        when(repository.findById(8L)).thenReturn(Optional.empty());

        assertThat(service.getById(7L)).isSameAs(entity);
        assertThatThrownBy(() -> service.getById(8L))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
