package io.github.imnotstable.msc.bench;

import java.util.Locale;

/**
 * What a reader should see of run text (#677, #678), written apart from {@link Quoted} so a test does not check the
 * escape against itself: the text on one line with every character that splits a line or hides itself (a control or
 * C1 character, a format character such as a zero-width space, a bidi override or isolate, a line or paragraph
 * separator, a space other than the plain one, an unassigned or private-use character, a lone surrogate) written as an
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
     * ASCII punctuation dropped.
     */
    public static String unescapeMarkdown(String markdown) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            if (c == '\\' && i + 1 < markdown.length() && isAsciiPunctuation(markdown.charAt(i + 1))) {
                out.append(markdown.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isAsciiPunctuation(char c) {
        return c < 128 && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    private static boolean hidden(int c) {
        if (c == ' ') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SPACE_SEPARATOR
                || type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE;
    }
}
