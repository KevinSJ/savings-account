package com.takehome.savings.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Checks free-text nicknames against a blocklist of offensive terms.
 *
 * <p>Matching runs in three passes over increasingly aggressive normalisations:
 * <ol>
 *   <li><b>Tokens</b> - NFKC-folded, lowercased, non-alphanumerics turned into
 *       separators; an exact word hit matches ("stupid", "ＳＴＵＰＩＤ").</li>
 *   <li><b>Letter skeleton</b> - letters only; catches letter-separated
 *       obfuscation ("s-t-u-p-i-d") and glued terms inside longer words
 *       ("dumb4ss" contains "dumb..."), restricted to blocklist entries of 5+
 *       letters so "hello" never trips on "hell".</li>
 *   <li><b>Leet patterns</b> - only when the input carries digits or the classic
 *       substitution symbols ({@code @ $ ! | +}); each banned word of 4+ letters
 *       becomes a regex with per-character ambiguity classes
 *       (a=4@, e=3, i=1!|, o=0, u=0, s=5$, t=7+, l=1|, b=8, g=9, z=2), so
 *       {@code h311}, {@code he11}, {@code d4mn}, {@code st0pid}, {@code bl00dy}
 *       are caught while digit-free text stays subject to the strict passes only.</li>
 * </ol>
 *
 * <p>Honest residual gaps (deliberate): confusable homoglyphs (Cyrillic а),
 * substitutions outside the class map ("stup1d" u→1 is nonsense but "stvpid" v→u
 * is conceivable), and semantic paraphrase. This is a cheap first gate, not a
 * moderation platform.
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

    /** Shortest banned term eligible for the leet-pattern pass (digits present). */
    static final int MIN_PATTERN_LENGTH = 4;

    /** Substitution symbols that arm the leet-pattern pass. */
    private static final String PATTERN_TRIGGERS = "@$!|+";

    /** Per-character ambiguity classes for the leet-pattern pass. */
    private static final Map<Character, String> LEET_CLASSES = Map.ofEntries(
            Map.entry('a', "[a4@]"),
            Map.entry('b', "[b8]"),
            Map.entry('e', "[e3]"),
            Map.entry('g', "[g9]"),
            Map.entry('i', "[i1!|]"),
            Map.entry('l', "[l1|]"),
            Map.entry('o', "[o0]"),
            Map.entry('s', "[s5$]"),
            Map.entry('t', "[t7+]"),
            Map.entry('u', "[u0]"),
            Map.entry('z', "[z2]"));

    private final Set<String> bannedWords;
    private final Set<String> bannedSkeletons;
    private final List<Pattern> leetPatterns;

    @Autowired
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
        this.leetPatterns = this.bannedWords.stream()
                .filter(word -> word.length() >= MIN_PATTERN_LENGTH)
                .map(OffensiveWordFilter::compileLeetPattern)
                .toList();
    }

    /**
     * @return the first offending term if {@code text} contains one, empty otherwise.
     *         {@code null} or blank text is never offensive (absence is handled upstream).
     */
    public Optional<String> firstOffensiveMatch(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<String> byToken = tokenMatch(normalize(text));
        if (byToken.isPresent()) {
            return byToken;
        }
        // Skeleton pass is substring-based (catches glued obfuscation) but only for words
        // long enough that everyday substrings are unlikely - otherwise "hello" would
        // trip on "hell" (the Scunthorpe problem, deliberately blunted, not solved).
        Optional<String> bySkeleton = bannedSkeletons.stream()
                .filter(word -> word.length() >= MIN_SUBSTRING_LENGTH)
                .filter(skeleton(text)::contains)
                .findFirst();
        if (bySkeleton.isPresent()) {
            return bySkeleton;
        }
        return leetMatch(text);
    }

    private Optional<String> tokenMatch(String normalized) {
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty() && bannedWords.contains(token)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /**
     * Symbol-substitution pass: armed only when the input itself carries digits or
     * substitution symbols, so natural letter-only text is immune to its relaxed
     * threshold (the Scunthorpe guard stays intact for normal words).
     */
    private Optional<String> leetMatch(String text) {
        String raw = rawView(text);
        boolean armed = raw.chars()
                .anyMatch(c -> Character.isDigit(c) || PATTERN_TRIGGERS.indexOf(c) >= 0);
        if (!armed) {
            return Optional.empty();
        }
        List<String> patternWords = bannedWords.stream()
                .filter(word -> word.length() >= MIN_PATTERN_LENGTH)
                .toList();
        for (int i = 0; i < leetPatterns.size(); i++) {
            if (leetPatterns.get(i).matcher(raw).find()) {
                return Optional.of(patternWords.get(i));
            }
        }
        return Optional.empty();
    }

    /**
     * The raw matching view: NFKC + lowercase, everything else preserved so the
     * ambiguity classes can match digits/symbols where they stand.
     */
    static String rawView(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /** "hell" -> "h[e3][l1|][l1|]"; chars outside the class map stay literal. */
    static Pattern compileLeetPattern(String word) {
        StringBuilder regex = new StringBuilder();
        for (char c : word.toCharArray()) {
            String clazz = LEET_CLASSES.get(c);
            regex.append(clazz != null ? clazz : String.valueOf(c));
        }
        return Pattern.compile(regex.toString());
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
