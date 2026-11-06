package com.takehome.savings.api.dto;

import com.takehome.savings.api.validation.NotOffensive;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a savings account.
 *
 * <p>Validation contract:
 * <ul>
 *   <li>{@code customerName}: mandatory; letters, spaces, hyphens, apostrophes and
 *       periods only - a minimal anti-abuse shape, documented as an assumption.</li>
 *   <li>{@code nickname}: optional ({@code null} allowed); when present it must be
 *       5-30 characters and pass the offensive-term screen. Blank is treated as
 *       invalid (did the caller mean "no nickname"? send {@code null}).</li>
 *   <li>There is deliberately no id/account-number field: both are server-generated,
 *       so caller-supplied values cannot exist, let alone be trusted.</li>
 * </ul>
 */
public record CreateAccountRequest(

        @NotBlank(message = "customerName is mandatory")
        @Size(max = 100, message = "customerName must be at most 100 characters")
        @Pattern(
                regexp = "[\\p{L}][\\p{L}\\p{M}'’. \\-]*",
                message = "customerName may contain letters, spaces, hyphens, apostrophes and periods only")
        String customerName,

        @Size(min = 5, max = 30, message = "nickname must be between 5 and 30 characters")
        @NotOffensive
        String nickname) {
}
