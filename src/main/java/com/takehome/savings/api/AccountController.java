package com.takehome.savings.api;

import java.net.URI;

import com.takehome.savings.api.dto.AccountResponse;
import com.takehome.savings.api.dto.CreateAccountRequest;
import com.takehome.savings.domain.SavingsAccount;
import com.takehome.savings.service.AccountService;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP boundary for savings accounts.
 *
 * <p>The controller is deliberately thin: validate the edge (bean validation on the
 * request record), translate domain results to/from wire records, and return correct
 * status codes. All business rules live in {@link AccountService}.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Creates an account; the payload was already validated against the request record. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        SavingsAccount created = accountService.create(request.customerName(), request.nickname());
        AccountResponse body = AccountResponse.from(created);
        URI location = UriComponentsBuilder.fromPath("/api/v1/accounts/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        log.info("Created account id={} for customer [redacted]", created.getId());
        return ResponseEntity.created(location).body(body);
    }

    /** Fetches one account by id. */
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable long id) {
        return AccountResponse.from(accountService.getById(id));
    }
}
