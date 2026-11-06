package com.takehome.savings.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Rejects values containing offensive terms, backed by
 * {@code OffensiveWordFilter} (bean-validation sees it via the validator below).
 *
 * <p>Bean-validation-conformant: {@code null} passes (optionality belongs to
 * {@code @Size}/{@code @NotBlank}); the rule only fires when a value is present.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotOffensiveValidator.class)
public @interface NotOffensive {

    String message() default "{com.takehome.savings.api.validation.NotOffensive.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
