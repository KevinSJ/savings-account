package com.takehome.savings.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Checks free-text nicknames against a blocklist of offensive terms.
 *
 * <p>Mechanical obfuscation is defeated by comparing three normalised views of the input:
 * <ol>
 *   <li>whole-string lowercase tokens ("dumb" hidden as its own word),</li>
 *   <li>the alphanumeric skeleton with leetspeak folded back ("d-u-m-b", "dumb4ss"),</li>
 *   <li>NFKC normalisation kills lookalike unicode variants of ASCII.</li>
 * </ol>
 *
 * <p>TODO(moderation): a shipped word list is a blunt instrument - false positives
 * ("Scunthorpe problem"), coverage gaps, and no context awareness. A production system
 * delegates to a moderation service (e.g. a hosted content-moderation API) plus human
 * review, keeping this filter only as a cheap first gate.
 */
@Component
public class OffensiveWordFilter {

    /** Blocklist resource, override-able via constructor for tests/config. */
    static final String DEFAULT_WORD_LIST = "classpath:offensive-words.txt";

    /** Shortest banned term eligible for the aggressive substring pass. */
    static final int MIN_SUBSTRING_LENGTH = 5;

    private final Set<String> bannedWords;
    private final Set<String> bannedSkeletons;

    public OffensiveWordFilter(ResourceLoader resourceLoader) {
        this(readWords(resourceLoader, DEFAULT_WORD_LIST));
    }

    /** For tests and alternative blocklist sources; words are stored normalised. */
    public OffensiveWordFilter(List<String> rawWords) {
        this.bannedWords = rawWords.stream()
                .map(OffensiveWordFilter::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.bannedSkeletons = this.bannedWords.stream()
                .map(OffensiveWordFilter::skeleton)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @return the first offending term if {@code text} contains one, empty otherwise.
     *         {@code null} or blank text is never offensive (absence is handled upstream).
     */
    public Optional<String> firstOffensiveMatch(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty() && bannedWords.contains(token)) {
                return Optional.of(token);
            }
        }
        // Skeleton pass is substring-based (catches glued obfuscation) but only for words
        // long enough that everyday substrings are unlikely - otherwise "hello" would
        // trip on "hell" (the Scunthorpe problem, deliberately blunted, not solved).
        String skeleton = skeleton(text);
        return bannedSkeletons.stream()
                .filter(word -> word.length() >= MIN_SUBSTRING_LENGTH)
                .filter(skeleton::contains)
                .findFirst();
    }

    /**
     * NFKC-fold, lowercase and trim separators to spaces so "d_u_m_b" tokenises cleanly.
     */
    static String normalize(String input) {
        String nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return nfkc.chars()
                .mapToObj(c -> Character.isLetterOrDigit(c) ? String.valueOf((char) c) : " ")
                .collect(Collectors.joining());
    }

    /**
     * Aggressive view: letters/digits only, leetspeak mapped to letters
     * (0->o, 1->i/l, 3->e, 4->a, 5->s, 7->t, @->a, $->s). Catches glued and
     * symbol-separated obfuscation.
     */
    static String skeleton(String input) {
        String nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (char c : nfkc.toCharArray()) {
            switch (c) {
                case '0' -> out.append('o');
                case '1', '!', '|' -> out.append('i');
                case '3' -> out.append('e');
                case '4', '@' -> out.append('a');
                case '5', '$' -> out.append('s');
                case '7', '+' -> out.append('t');
                default -> {
                    if (Character.isLetter(c)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static List<String> readWords(ResourceLoader loader, String location) {
        try {
            Resource resource = loader.getResource(location);
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        } catch (Exception e) {
            // Fail fast: silently accepting every nickname would disable the rule entirely.
            throw new IllegalStateException("Cannot load offensive-word list: " + location, e);
        }
    }
}
