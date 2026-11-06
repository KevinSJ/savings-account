package com.takehome.savings.api.error;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.takehome.savings.service.AccountLimitExceededException;
import com.takehome.savings.service.AccountNotFoundException;
import com.takehome.savings.service.AccountNumberAllocationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * Maps every failure mode to RFC 9457 problem+json with a deliberate status code:
 *
 * <ul>
 *   <li>400 - malformed/unvalidated request bodies, unreadable payloads</li>
 *   <li>404 - account id does not exist</li>
 *   <li>409 - business-rule conflict (max accounts per customer)</li>
 *   <li>503 - infrastructure unavailable (Postgres down) or transient contention -
 *       clients are told the request is retryable</li>
 *   <li>500 - everything else, logged with full detail but never leaked to callers</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String TYPE_PREFIX = "https://api.takehome.co.nz/errors/";
    private static final String DB_UNAVAILABLE_DETAIL =
            "The datastore is temporarily unavailable; please retry shortly.";

    // ---- request-facing errors ---------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onBeanValidationFailure(MethodArgumentNotValidException ex) {
        List<FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldIssue(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "Request body failed validation");
        problem.setProperty("issues", issues);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail onMethodValidation(HandlerMethodValidationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request parameters failed validation");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-body",
                "Request body is missing or not valid JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-parameter",
                "Parameter '%s' has an invalid format".formatted(ex.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail onMissingParameter(MissingServletRequestParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, "missing-parameter",
                "Parameter '%s' is required".formatted(ex.getParameterName()));
    }

    // ---- domain errors ------------------------------------------------------------------------

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail onNotFound(AccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found", ex.getMessage());
    }

    @ExceptionHandler(AccountLimitExceededException.class)
    ProblemDetail onLimitReached(AccountLimitExceededException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "account-limit-reached", ex.getMessage());
        problem.setProperty("customerName", ex.getCustomerName());
        problem.setProperty("maxAccounts", ex.getMaxAccounts());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", ex.getMessage());
    }

    // ---- infrastructure errors: datastore unavailable / transient ------------------------------

    @ExceptionHandler({
            CannotGetJdbcConnectionException.class,
            CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class,
            QueryTimeoutException.class,
            TransientDataAccessException.class,
            RecoverableDataAccessException.class,
            JpaSystemException.class
    })
    ProblemDetail onDatastoreUnavailable(Exception ex) {
        log.error("Datastore unavailable or transient failure: {}", ex.toString());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "datastore-unavailable",
                DB_UNAVAILABLE_DETAIL);
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(AccountNumberAllocationException.class)
    ProblemDetail onAllocationExhaustion(AccountNumberAllocationException ex) {
        log.error("Account-number allocation exhausted after {} attempts", ex.getAttempts());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "allocation-unavailable",
                "Could not allocate an account number right now; please retry.");
    }

    /**
     * Integrity violations are rare in the happy path (generator + advisory lock), so a
     * 409 Conflict is the honest signal instead of pretending it is a server fault.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "conflict",
                "The request conflicts with existing data.");
    }

    /** Lock-contention guard rail: the advisory lock could not be taken in time. */
    @ExceptionHandler(CannotAcquireLockException.class)
    ProblemDetail onLockContention(CannotAcquireLockException ex) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "contention",
                "Too much contention on this customer right now; please retry.");
        problem.setProperty("retryable", true);
        return problem;
    }

    // ---- routing errors (kept out of the generic 500 fallback) ---------------------------------

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ProblemDetail onUnknownRoute(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "route-not-found", "No such resource.");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ProblemDetail onBadMethod(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
                "Method not supported on this resource.");
    }

    // ---- fallback ------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred.");
    }

    private ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    record FieldIssue(String field, String message) {
    }
}
