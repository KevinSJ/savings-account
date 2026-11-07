package com.takehome.savings.api.dto;

import com.takehome.savings.api.validation.NotOffensive;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = """
        Nickname is stripped before validation; a whitespace-only nickname is a 400, not an
        absent nickname. Use null / omit the field for "no nickname".""")
public record CreateAccountRequest(

        @Schema(description = "Mandatory; letters, spaces, hyphens, apostrophes and periods only",
                example = "Alice Tester", minLength = 1, maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "customerName is mandatory")
        @Size(min = 1, max = 100, message = "customerName must be between 1 and 100 characters")
        @Pattern(
                regexp = "[\\p{L}][\\p{L}\\p{M}'’. \\-]*",
                message = "customerName may contain letters, spaces, hyphens, apostrophes and periods only")
        String customerName,

        @Schema(description = "Optional human label, screened against the offensive-term list",
                example = "rainy day", minLength = 5, maxLength = 30)
        @Size(min = 5, max = 30, message = "nickname must be between 5 and 30 characters")
        @NotOffensive
        String nickname) {

    /**
     * Nickname is stripped BEFORE validation so the stored value honours its own
     * 5-30 contract and a whitespace-only nickname correctly fails {@code @Size}
     * instead of slipping through as "5 characters of nothing" (review finding #3).
     */
    public CreateAccountRequest {
        if (nickname != null) {
            nickname = nickname.strip();
        }
    }
}
