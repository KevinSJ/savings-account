package com.takehome.savings.api.validation;

import com.takehome.savings.service.OffensiveWordFilter;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Bridges {@link NotOffensive} to the {@link OffensiveWordFilter}.
 *
 * <p>Instantiated by Spring's constraint-validator factory, so the filter (and its
 * word list) participates in normal dependency injection.
 */
public class NotOffensiveValidator implements ConstraintValidator<NotOffensive, CharSequence> {

    private final OffensiveWordFilter filter;

    public NotOffensiveValidator(OffensiveWordFilter filter) {
        this.filter = filter;
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // optional-ness is a separate rule's job
        }
        return filter.firstOffensiveMatch(value.toString()).isEmpty();
    }
}
