package com.takehome.savings.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OffensiveWordFilterTest {

    private final OffensiveWordFilter filter =
            new OffensiveWordFilter(List.of("damn", "hell", "bloody", "stupid", "moron", "dumb"));

    @Test
    @DisplayName("flags a plain word, case-insensitively")
    void plainMatch() {
        assertThat(filter.firstOffensiveMatch("what a Moron")).isPresent();
        assertThat(filter.firstOffensiveMatch("STUPID idea")).isPresent();
    }

    @Test
    @DisplayName("letter-separated obfuscation is caught via the skeleton pass")
    void obfuscatedMatch() {
        assertThat(filter.firstOffensiveMatch("s-t-u-p-i-d")).isPresent();
        assertThat(filter.firstOffensiveMatch("stup!d")).isPresent();
        assertThat(filter.firstOffensiveMatch("bl00dy")).isPresent(); // o->0
        assertThat(filter.firstOffensiveMatch("blo0dy")).isPresent();
        // 4-letter banned terms ("dumb") only match as whole tokens: the substring pass
        // needs >= 5 letters, otherwise "hello" trips "hell" and "dumbbell" trips "dumb".
        assertThat(filter.firstOffensiveMatch("dumbbell workout log")).isEmpty();
    }

    @Test
    @DisplayName("symbol-substitution vectors caught standing alone (review finding #6)")
    void leetPatternPassStandsAlone() {
        // Each of these failed before the leet-pattern pass existed: the old
        // "blo0dy h311" test masked the gap by matching blo0dy only.
        assertThat(filter.firstOffensiveMatch("h311")).isPresent();   // hell, 1<->l
        assertThat(filter.firstOffensiveMatch("he11")).isPresent();   // hell
        assertThat(filter.firstOffensiveMatch("st0pid")).isPresent(); // stupid, 0<->u
        assertThat(filter.firstOffensiveMatch("d4mn")).isPresent();   // damn
        assertThat(filter.firstOffensiveMatch("my h311 jar")).isPresent();
    }

    @Test
    @DisplayName("digit-free text stays immune to the relaxed leet threshold")
    void leetPassStaysDisarmedWithoutDigits() {
        // 'hell' inside natural words must still be safe when no digits/symbols present.
        assertThat(filter.firstOffensiveMatch("hello there")).isEmpty();
        assertThat(filter.firstOffensiveMatch("shell holiday fund")).isEmpty();
        assertThat(filter.firstOffensiveMatch("stupidity allowance")).isPresent(); // token: stupidity
    }

    @Test
    @DisplayName("clean nicknames pass")
    void cleanPasses() {
        assertThat(filter.firstOffensiveMatch("Rainy Day Fund")).isEmpty();
        assertThat(filter.firstOffensiveMatch("holiday-money")).isEmpty();
        assertThat(filter.firstOffensiveMatch(null)).isEmpty();
        assertThat(filter.firstOffensiveMatch("   ")).isEmpty();
    }

    @Test
    @DisplayName("short banned words are exempt from substring matching (Scunthorpe guard)")
    void shortWordsDoNotSubstringMatch() {
        // 'hell' (4 chars) must not fire inside 'shell holiday fund'
        assertThat(filter.firstOffensiveMatch("shell holiday fund")).isEmpty();
        // ...but still fires as a standalone token
        assertThat(filter.firstOffensiveMatch("go to hell")).isPresent();
    }

    @Test
    @DisplayName("normalisation is unicode-aware")
    void normalisationAware() {
        // fullwidth characters fold to ASCII under NFKC
        assertThat(filter.firstOffensiveMatch("ｄａｍｎ")).isPresent();
    }

    @Test
    @DisplayName("empty blocklist never matches")
    void emptyBlocklist() {
        OffensiveWordFilter empty = new OffensiveWordFilter(List.of());
        assertThat(empty.firstOffensiveMatch("anything at all")).isEmpty();
    }
}
