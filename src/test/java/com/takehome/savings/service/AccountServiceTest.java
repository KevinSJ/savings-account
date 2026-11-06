package com.takehome.savings.service;

import java.util.Optional;

import com.takehome.savings.domain.SavingsAccount;
import com.takehome.savings.repository.SavingsAccountRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

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
    @DisplayName("create: blank customer name is rejected before touching the database")
    void blankNameRejected() {
        assertThatThrownBy(() -> service.create("   ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).acquireCustomerAccountLock(anyString());
        verify(repository, never()).save(any());
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
