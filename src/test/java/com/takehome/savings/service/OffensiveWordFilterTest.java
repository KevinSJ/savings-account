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
    @DisplayName("separator-obfuscated words are caught via the skeleton pass")
    void obfuscatedMatch() {
        assertThat(filter.firstOffensiveMatch("s-t-u-p-i-d")).isPresent();
        assertThat(filter.firstOffensiveMatch("stup!d")).isPresent();
        assertThat(filter.firstOffensiveMatch("blo0dy h311")).isPresent();
        // 4-letter banned terms ("dumb") only match as whole tokens: the substring pass
        // needs >= 5 letters, otherwise "hello" trips "hell" and "dumbbell" trips "dumb".
        assertThat(filter.firstOffensiveMatch("dumbbell workout log")).isEmpty();
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
