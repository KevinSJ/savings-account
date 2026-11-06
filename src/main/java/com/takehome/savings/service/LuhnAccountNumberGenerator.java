package com.takehome.savings.service;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

/**
 * 12-digit account numbers: 11 random digits + one Luhn check digit.
 *
 * <p>Why not a plain database sequence?
 * <ul>
 *   <li>A strictly increasing number leaks account-opening volume and is guessable -
 *       poor properties for something customers quote over the phone.</li>
 *   <li>The Luhn digit is the same defence card numbers use: a cheap integrity check that
 *       catches transposition typos before the number is ever sent to us (e.g. a future
 *       "lookup by account number" endpoint can reject garbage for free).</li>
 * </ul>
 * Trade-off: randomness means collisions, so the service must probe-and-retry; measured
 * in attempts, a birthday-paradox collision for this space (10^11) is vanishingly rare.
 */
@Component
public class LuhnAccountNumberGenerator implements AccountNumberGenerator {

    /** Digits before the check digit. Total printed length becomes 12. */
    static final int BASE_DIGITS = 11;

    private final RandomGenerator random;

    public LuhnAccountNumberGenerator() {
        this(new SecureRandom());
    }

    /** Visible for tests that need deterministic digit streams. */
    LuhnAccountNumberGenerator(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public String generate() {
        StringBuilder base = new StringBuilder(BASE_DIGITS);
        for (int i = 0; i < BASE_DIGITS; i++) {
            base.append((char) ('0' + random.nextInt(10)));
        }
        // Keep the leading digit non-zero so the number reads as a full-width 12-digit id.
        base.setCharAt(0, (char) ('1' + random.nextInt(9)));
        return base.toString() + luhnCheckDigit(base);
    }

    /**
     * Standard Luhn: starting from the rightmost base digit, double every second digit,
     * sum digits of products, and return the digit that zeroes the total mod 10.
     */
    static char luhnCheckDigit(CharSequence baseDigits) {
        int sum = 0;
        boolean doubleIt = true; // the check digit position itself is "doubled" first
        for (int i = baseDigits.length() - 1; i >= 0; i--) {
            int digit = Character.digit(baseDigits.charAt(i), 10);
            if (digit < 0) {
                throw new IllegalArgumentException("Non-digit in account base: " + baseDigits);
            }
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    /** Full-number Luhn validation, usable by callers for integrity checks. */
    public static boolean isLuhnValid(String number) {
        if (number == null || number.length() < 2 || !number.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false; // "number" includes the check digit at the rightmost slot
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
