package com.takehome.savings.service;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuhnAccountNumberGeneratorTest {

    private final LuhnAccountNumberGenerator generator = new LuhnAccountNumberGenerator();

    @Test
    @DisplayName("generated numbers are 12 digits, non-zero-leading and Luhn-valid")
    void generatesValidNumbers() {
        for (int i = 0; i < 1000; i++) {
            String number = generator.generate();
            assertThat(number).hasSize(12).matches("\\d{12}");
            assertThat(number.charAt(0)).isNotEqualTo('0');
            assertThat(LuhnAccountNumberGenerator.isLuhnValid(number)).as(number).isTrue();
        }
    }

    @Test
    @DisplayName("collisions are negligible across a sane sample")
    void spreadIsWide() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    @DisplayName("Luhn check digit matches the canonical worked example (7992739871 -> 3)")
    void usesStandardLuhnAlgorithm() {
        assertThat(LuhnAccountNumberGenerator.luhnCheckDigit("7992739871")).isEqualTo('3');
        assertThat(LuhnAccountNumberGenerator.isLuhnValid("79927398713")).isTrue();
        assertThat(LuhnAccountNumberGenerator.isLuhnValid("79927398714")).isFalse();
    }

    @Test
    @DisplayName("validity check rejects junk")
    void validityRejectsJunk() {
        assertThat(LuhnAccountNumberGenerator.isLuhnValid(null)).isFalse();
        assertThat(LuhnAccountNumberGenerator.isLuhnValid("")).isFalse();
        assertThat(LuhnAccountNumberGenerator.isLuhnValid("1234A")).isFalse();
        assertThat(LuhnAccountNumberGenerator.isLuhnValid("3")).isFalse();
    }

    @Test
    @DisplayName("non-digit base is rejected loudly")
    void rejectsNonDigitBase() {
        assertThatThrownBy(() -> LuhnAccountNumberGenerator.luhnCheckDigit("123X5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deterministic RNG still yields Luhn-valid numbers")
    void worksWithInjectedRandom() {
        RandomGenerator fixed = new java.util.Random(42);
        LuhnAccountNumberGenerator deterministic = new LuhnAccountNumberGenerator(fixed);
        for (int i = 0; i < 100; i++) {
            assertThat(LuhnAccountNumberGenerator.isLuhnValid(deterministic.generate())).isTrue();
        }
    }
}
