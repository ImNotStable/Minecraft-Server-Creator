package io.github.imnotstable.msc.bench;

import java.util.BitSet;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * What a reader should see of run text (#677, #678), written apart from {@link Quoted} so a test does not check the
 * escape against itself: the text on one line with every character that splits a line or hides itself (a control or
 * C1 character, a format character such as a zero-width space, a bidi override or isolate, a line or paragraph
 * separator, a space other than the plain one, an unassigned or private-use character, a lone surrogate, any character
 * Unicode calls default ignorable, such as a Hangul filler or a variation selector) written as an
 * escape, and every other character as it is.
 */
public final class ReaderView {

    private ReaderView() {
    }

    /** {@code text} as a reader should see it on the terminal. */
    public static String expected(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (hidden(c)) {
                        for (char unit : Character.toChars(c)) {
                            out.append("\\u").append(String.format(Locale.ROOT, "%04x", (int) unit));
                        }
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
            i += Character.charCount(c);
        }
        return out.toString();
    }

    /** The first character in {@code text} a reader could not see, as {@code U+XXXX}, or null when there is none. */
    public static String firstHidden(String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (c != '\n' && hidden(c)) {
                return String.format(Locale.ROOT, "U+%04X at %d", c, i);
            }
            i += Character.charCount(c);
        }
        return null;
    }

    /**
     * {@code markdown} as a Markdown renderer shows it when it holds only backslash escapes: each backslash before
     * ASCII punctuation dropped, except inside an extended autolink (#685). GitHub's Markdown links a bare URL straight
     * from the source, from where it starts to the next blank or {@code <}, so a backslash there is shown as it is.
     */
    public static String unescapeMarkdown(String markdown) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            if (autolinkAt(markdown, i)) {
                while (i < markdown.length() && !Character.isWhitespace(markdown.charAt(i))
                        && markdown.charAt(i) != '<') {
                    out.append(markdown.charAt(i++));
                }
                i--;
            } else if (c == '\\' && i + 1 < markdown.length() && isAsciiPunctuation(markdown.charAt(i + 1))) {
                out.append(markdown.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Whether an extended autolink of the GitHub Flavored Markdown spec starts at {@code i}, a little more often than
     * GitHub finds one: {@code www.} after the start, a blank or one of {@code *_~(}, or {@code http://},
     * {@code https://} or {@code ftp://} (any case) not right after a letter, each followed by a character that is no
     * blank and no {@code <}.
     */
    private static boolean autolinkAt(String markdown, int i) {
        int start;
        if (markdown.startsWith("www.", i)) {
            char before = i > 0 ? markdown.charAt(i - 1) : ' ';
            if (!Character.isWhitespace(before) && "*_~(".indexOf(before) < 0) {
                return false;
            }
            start = i + 4;
        } else {
            String scheme = Stream.of("http://", "https://", "ftp://")
                    .filter(s -> markdown.regionMatches(true, i, s, 0, s.length())).findFirst().orElse(null);
            if (scheme == null || i > 0 && Character.isLetter(markdown.charAt(i - 1))) {
                return false;
            }
            start = i + scheme.length();
        }
        return start < markdown.length() && !Character.isWhitespace(markdown.charAt(start))
                && markdown.charAt(start) != '<';
    }

    private static boolean isAsciiPunctuation(char c) {
        return c < 128 && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    /** Whether a reader could not see {@code c}: a character of these categories, or a default ignorable one. */
    public static boolean hidden(int c) {
        if (c == ' ') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SPACE_SEPARATOR
                || type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
                || DEFAULT_IGNORABLE.get(c);
    }

    /**
     * Default_Ignorable_Code_Point as Unicode publishes it (#688): the lines of DerivedCoreProperties-16.0.0.txt
     * (https://www.unicode.org/Public/16.0.0/ucd/DerivedCoreProperties.txt) copied as they are, read here by their
     * own format, so the model does not share {@link Quoted}'s list.
     */
    private static final String DERIVED_CORE_PROPERTIES = """
            00AD          ; Default_Ignorable_Code_Point # Cf       SOFT HYPHEN
            034F          ; Default_Ignorable_Code_Point # Mn       COMBINING GRAPHEME JOINER
            061C          ; Default_Ignorable_Code_Point # Cf       ARABIC LETTER MARK
            115F..1160    ; Default_Ignorable_Code_Point # Lo   [2] HANGUL CHOSEONG FILLER..HANGUL JUNGSEONG FILLER
            17B4..17B5    ; Default_Ignorable_Code_Point # Mn   [2] KHMER VOWEL INHERENT AQ..KHMER VOWEL INHERENT AA
            180B..180D    ; Default_Ignorable_Code_Point # Mn   [3] MONGOLIAN FREE VARIATION SELECTOR ONE..MONGOLIAN FREE VARIATION SELECTOR THREE
            180E          ; Default_Ignorable_Code_Point # Cf       MONGOLIAN VOWEL SEPARATOR
            180F          ; Default_Ignorable_Code_Point # Mn       MONGOLIAN FREE VARIATION SELECTOR FOUR
            200B..200F    ; Default_Ignorable_Code_Point # Cf   [5] ZERO WIDTH SPACE..RIGHT-TO-LEFT MARK
            202A..202E    ; Default_Ignorable_Code_Point # Cf   [5] LEFT-TO-RIGHT EMBEDDING..RIGHT-TO-LEFT OVERRIDE
            2060..2064    ; Default_Ignorable_Code_Point # Cf   [5] WORD JOINER..INVISIBLE PLUS
            2065          ; Default_Ignorable_Code_Point # Cn       <reserved-2065>
            2066..206F    ; Default_Ignorable_Code_Point # Cf  [10] LEFT-TO-RIGHT ISOLATE..NOMINAL DIGIT SHAPES
            3164          ; Default_Ignorable_Code_Point # Lo       HANGUL FILLER
            FE00..FE0F    ; Default_Ignorable_Code_Point # Mn  [16] VARIATION SELECTOR-1..VARIATION SELECTOR-16
            FEFF          ; Default_Ignorable_Code_Point # Cf       ZERO WIDTH NO-BREAK SPACE
            FFA0          ; Default_Ignorable_Code_Point # Lo       HALFWIDTH HANGUL FILLER
            FFF0..FFF8    ; Default_Ignorable_Code_Point # Cn   [9] <reserved-FFF0>..<reserved-FFF8>
            1BCA0..1BCA3  ; Default_Ignorable_Code_Point # Cf   [4] SHORTHAND FORMAT LETTER OVERLAP..SHORTHAND FORMAT UP STEP
            1D173..1D17A  ; Default_Ignorable_Code_Point # Cf   [8] MUSICAL SYMBOL BEGIN BEAM..MUSICAL SYMBOL END PHRASE
            E0000         ; Default_Ignorable_Code_Point # Cn       <reserved-E0000>
            E0001         ; Default_Ignorable_Code_Point # Cf       LANGUAGE TAG
            E0002..E001F  ; Default_Ignorable_Code_Point # Cn  [30] <reserved-E0002>..<reserved-E001F>
            E0020..E007F  ; Default_Ignorable_Code_Point # Cf  [96] TAG SPACE..CANCEL TAG
            E0080..E00FF  ; Default_Ignorable_Code_Point # Cn [128] <reserved-E0080>..<reserved-E00FF>
            E0100..E01EF  ; Default_Ignorable_Code_Point # Mn [240] VARIATION SELECTOR-17..VARIATION SELECTOR-256
            E01F0..E0FFF  ; Default_Ignorable_Code_Point # Cn [3600] <reserved-E01F0>..<reserved-E0FFF>
            """;

    private static final BitSet DEFAULT_IGNORABLE = new BitSet();

    static {
        for (String line : DERIVED_CORE_PROPERTIES.lines().toList()) {
            String[] range = line.substring(0, line.indexOf(';')).strip().split("\\.\\.");
            int first = Integer.parseInt(range[0], 16);
            int last = Integer.parseInt(range[range.length - 1], 16);
            // the count in brackets is the file's own check on the range
            int count = line.contains("[") ? Integer.parseInt(line.replaceAll(".*\\[(\\d+)].*", "$1")) : 1;
            if (last - first + 1 != count) {
                throw new IllegalStateException("range and count disagree: " + line);
            }
            DEFAULT_IGNORABLE.set(first, last + 1);
        }
    }
}
