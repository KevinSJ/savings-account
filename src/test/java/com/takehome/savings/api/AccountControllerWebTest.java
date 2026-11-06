package com.takehome.savings.api;

import java.util.UUID;

import com.takehome.savings.api.error.GlobalExceptionHandler;
import com.takehome.savings.domain.SavingsAccount;
import com.takehome.savings.service.AccountLimitExceededException;
import com.takehome.savings.service.AccountNotFoundException;
import com.takehome.savings.service.AccountNumberAllocationException;
import com.takehome.savings.service.AccountService;
import com.takehome.savings.service.LockContentionException;
import com.takehome.savings.service.OffensiveWordFilter;
import com.takehome.savings.support.TestAccounts;
import com.takehome.savings.support.TestAccounts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * MVC slice tests: routing, bean validation, error mapping and content
 * negotiation without a database. Offensive-word screening runs for real via
 * {@link OffensiveWordFilter} (bean defined in {@link SliceBeans}).
 */
@WebMvcTest(AccountController.class)
class AccountControllerWebTest {

    /**
     * The slice does not scan the service package, so the offensive-word filter
     * gets an explicit bean. Nested {@code @TestConfiguration} appends to the
     * slice context; {@code @Import} would replace the slice's config entirely.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SliceBeans {
        @Bean
        OffensiveWordFilter offensiveWordFilter(ResourceLoader resourceLoader) {
            return new OffensiveWordFilter(resourceLoader);
        }
    }

    private static final UUID FIXTURE_ID =
            UUID.fromString("0b9e6c2e-1111-4a2b-9c3d-5e6f70899a0b");
    private static final String FIXTURE_NUMBER = "100000000002";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccountService accountService;

    // ---- create -----------------------------------------------------------------------------

    @Test
    void createReturnsMinimalBodyWithLocationHeader() throws Exception {
        // Requirement: the create response MUST NOT carry the id field; it MAY
        // carry the account number. The opaque id travels once, in Location.
        when(accountService.create(eq("Alice Tester"), eq("rainy-day")))
                .thenReturn(TestAccounts.persisted(9, FIXTURE_ID, FIXTURE_NUMBER, "Alice Tester", "rainy-day"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice Tester", "nickname": "rainy-day"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + FIXTURE_ID))
                .andExpect(jsonPath("$.accountNumber").value(FIXTURE_NUMBER))
                .andExpect(jsonPath("$.id").doesNotExist());
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
                .thenReturn(TestAccounts.persisted(9, FIXTURE_ID, "100000000001", "x", "hi"));
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
                .thenReturn(TestAccounts.persisted(9, FIXTURE_ID, "100000000002", "Alice", null));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/malformed-body"));
    }

    // ---- get ----------------------------------------------------------------------------------

    @Test
    void getReturnsFullDataModelWithNoStoreCacheControl() throws Exception {
        when(accountService.getByUid(FIXTURE_ID))
                .thenReturn(TestAccounts.persisted(9, FIXTURE_ID, FIXTURE_NUMBER, "Alice Tester", "rainy-day"));

        mockMvc.perform(get("/api/v1/accounts/" + FIXTURE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                // exactly the required data model: id (uuid value), account number,
                // customer name, optional nickname
                .andExpect(jsonPath("$.id").value(FIXTURE_ID.toString()))
                .andExpect(jsonPath("$.accountNumber").value(FIXTURE_NUMBER))
                .andExpect(jsonPath("$.customerName").value("Alice Tester"))
                .andExpect(jsonPath("$.nickname").value("rainy-day"));
    }

    @Test
    void getByNumberReturnsSameContract() throws Exception {
        when(accountService.getByAccountNumber("325748979052"))
                .thenReturn(TestAccounts.persisted(9, FIXTURE_ID, "325748979052", "Alice Tester", "rainy-day"));

        mockMvc.perform(get("/api/v1/accounts/by-number/325748979052"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.id").value(FIXTURE_ID.toString()));
    }

    @Test
    void missingAccountIs404Problem() throws Exception {
        when(accountService.getByUid(FIXTURE_ID)).thenThrow(new AccountNotFoundException(FIXTURE_ID.toString()));

        mockMvc.perform(get("/api/v1/accounts/" + FIXTURE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail")
                        .value("Savings account " + FIXTURE_ID + " does not exist"));
    }

    @Test
    void malformedIdIs400() throws Exception {
        // The 'id' path parameter is a UUID; any non-UUID value fails conversion.
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/invalid-parameter"));
    }

    @Test
    void sequentialIdProbeIsRejectedBeforeDatastore() throws Exception {
        // The historical sequential id space ('1', '2', ...) is no longer a
        // lookup surface at all - it 400s without touching Postgres.
        mockMvc.perform(get("/api/v1/accounts/1"))
                .andExpect(status().isBadRequest());
    }

    // ---- error mapping -----------------------------------------------------------------------

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
        when(accountService.getByUid(FIXTURE_ID))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        mockMvc.perform(get("/api/v1/accounts/" + FIXTURE_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/datastore-unavailable"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void lockContentionIs503() throws Exception {
        when(accountService.create(any(), any()))
                .thenThrow(new LockContentionException("Alice", java.time.Duration.ofSeconds(2)));

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
    void serverGeneratedNumberCollisionIsRetryable503Not409() throws Exception {
        // A duplicate SERVER-generated account number is the allocator losing a
        // probe-insert race - the caller did nothing wrong, so it must not read
        // as a client conflict.
        DataIntegrityViolationException collision =
                new DataIntegrityViolationException("could not execute statement",
                        new java.sql.SQLException(
                                "ERROR: duplicate key value violates unique constraint "
                                        + "\"uq_savings_accounts_account_number\" Detail: Key (account_number)=(123456789012) already exists."));
        when(accountService.create(any(), any())).thenThrow(collision);

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type")
                        .value("https://api.takehome.co.nz/errors/allocation-collision"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void otherIntegrityViolationsStay409() throws Exception {
        when(accountService.create(any(), any())).thenThrow(
                new DataIntegrityViolationException("not-null violation on some_other_column"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/conflict"));
    }

    @Test
    void unknownRouteIs404Not500() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.takehome.co.nz/errors/route-not-found"));
    }
}
