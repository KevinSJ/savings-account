package com.takehome.savings.api.validation;

import java.util.List;
import java.util.Set;

import com.takehome.savings.service.OffensiveWordFilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the annotation/validator wiring outside Spring MVC, using a validator
 * factory whose ConstraintValidatorFactory injects a filter with a known word list.
 */
class NotOffensiveValidatorTest {

    /** Sample bean exercising the constraint exactly as the DTO will. */
    static final class Sample {
        @NotOffensive
        final String nickname;

        Sample(String nickname) {
            this.nickname = nickname;
        }
    }

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        OffensiveWordFilter filter = new OffensiveWordFilter(List.of("moron"));
        ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new jakarta.validation.ConstraintValidatorFactory() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        return key == NotOffensiveValidator.class ? (T) new NotOffensiveValidator(filter) : null;
                    }

                    @Override
                    public void releaseInstance(ConstraintValidator<?, ?> instance) {
                        // nothing to release
                    }
                })
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void offensiveNicknameViolates() {
        Set<ConstraintViolation<Sample>> violations = validator.validate(new Sample("you moron"));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Nickname contains an offensive term");
    }

    @Test
    void cleanNicknamePasses() {
        assertThat(validator.validate(new Sample("Rainy Day Fund"))).isEmpty();
    }

    @Test
    void nullPasses_optionalityIsAnotherRulesJob() {
        assertThat(validator.validate(new Sample(null))).isEmpty();
    }
}
