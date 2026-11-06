package com.takehome.savings.api;

import java.time.Instant;
import java.util.Optional;

import com.takehome.savings.api.error.GlobalExceptionHandler;
import com.takehome.savings.service.AccountLimitExceededException;
import com.takehome.savings.service.AccountNotFoundException;
import com.takehome.savings.service.AccountNumberAllocationException;
import com.takehome.savings.service.AccountService;
import com.takehome.savings.service.OffensiveWordFilter;
import com.takehome.savings.support.TestAccounts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice tests: exercises routing, bean validation, error mapping and content
 * negotiation without a database. Offensive-word screening runs for real via
 * {@link OffensiveWordFilter} (imported into the slice).
 */
@WebMvcTest(AccountController.class)
class AccountControllerWebTest {

    /**
     * The slice does not run component scanning over the service package, so the
     * offensive-word filter gets an explicit bean. Nested {@code @TestConfiguration}
     * is appended to the slice context (unlike {@code @Import}, which would replace
     * the slice's default configuration sources).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SliceBeans {
        @Bean
        OffensiveWordFilter offensiveWordFilter(ResourceLoader resourceLoader) {
            return new OffensiveWordFilter(resourceLoader);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccountService accountService;

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(accountService.create(eq("Alice Tester"), eq("rainy-day")))
                .thenReturn(TestAccounts.persisted(1, "100000000002", "Alice Tester", "rainy-day"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice Tester", "nickname": "rainy-day"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountNumber").value("100000000002"))
                .andExpect(jsonPath("$.customerName").value("Alice Tester"))
                .andExpect(jsonPath("$.nickname").value("rainy-day"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void createWithoutCustomerNameIs400WithFieldIssues() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.issues[0].field").value("customerName"));
    }

    @Test
    void createWithBadNameCharactersIs400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Al1ce Tr0ut"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[0].field").value("customerName"));
    }

    @Test
    void createWithShortNicknameIs400() throws Exception {
        when(accountService.create(any(), any()))
                .thenReturn(TestAccounts.persisted(1, "100000000001", "x", "hi"));
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice", "nickname": "hi"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[0].field").value("nickname"));
    }

    @Test
    void createWithLongNicknameIs400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice", "nickname": "%s"}
                                """.formatted("x".repeat(31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[0].field").value("nickname"));
    }

    @Test
    void createWithOffensiveNicknameIs400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice", "nickname": "my stupid jar"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[0].field").value("nickname"))
                .andExpect(jsonPath("$.issues[0].message").value("Nickname contains an offensive term"));
    }

    @Test
    void nullNicknamePassesValidation() throws Exception {
        when(accountService.create(eq("Alice"), eq(null)))
                .thenReturn(TestAccounts.persisted(2, "100000000002", "Alice", null));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").doesNotExist());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/malformed-body"));
    }

    @Test
    void nonNumericAccountIdIs400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/invalid-parameter"));
    }

    @Test
    void missingAccountIs404Problem() throws Exception {
        when(accountService.getById(42L)).thenThrow(new AccountNotFoundException(42L));

        mockMvc.perform(get("/api/v1/accounts/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Savings account 42 does not exist"));
    }

    @Test
    void limitExceededIs409() throws Exception {
        when(accountService.create(any(), any()))
                .thenThrow(new AccountLimitExceededException("Alice", 5));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.maxAccounts").value(5));
    }

    @Test
    void databaseOutageIs503AndRetryable() throws Exception {
        when(accountService.getById(7L))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        mockMvc.perform(get("/api/v1/accounts/7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/datastore-unavailable"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void lockContentionIs503() throws Exception {
        when(accountService.create(any(), any()))
                .thenThrow(new CannotAcquireLockException("timeout waiting for advisory lock"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/contention"));
    }

    @Test
    void allocationExhaustionIs503() throws Exception {
        when(accountService.create(any(), any()))
                .thenThrow(new AccountNumberAllocationException(5));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/allocation-unavailable"));
    }

    @Test
    void unknownRouteIs404Not500() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/route-not-found"));
    }
}
