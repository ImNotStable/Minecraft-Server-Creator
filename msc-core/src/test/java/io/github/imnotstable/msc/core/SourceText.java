package io.github.imnotstable.msc.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What the source-scanning guard tests share (ADR 0035): Java or Kotlin source with comments and string contents
 * blanked, bracket pairs and call arguments. Blanking keeps every index and line, so a position found in the blanked
 * text is the same position in the source.
 */
final class SourceText {
    private SourceText() {
    }

    /** {@code source} with comments, and string and char contents if {@code strings}, blanked (newlines kept). */
    static String mask(String source, boolean strings) {
        char[] out = source.toCharArray();
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int e = source.indexOf('\n', i);
                i = blank(out, i, e < 0 ? n : e);
            } else if (source.startsWith("/*", i)) {
                int e = source.indexOf("*/", i + 2);
                i = blank(out, i, e < 0 ? n : e + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !source.startsWith("\"\"\"", j)) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                i = (strings ? blank(out, i + 3, j) : j) + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                i = (strings ? blank(out, i + 1, j) : j) + 1;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int blank(char[] out, int from, int to) {
        for (int k = from; k < to; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
        return to;
    }

    /** For each opening character, the index of its closing one (the text's end if unclosed); 0 elsewhere. */
    static int[] pairs(String bare, char open, char close) {
        int[] pairs = new int[bare.length()];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < bare.length(); i++) {
            if (bare.charAt(i) == open) {
                stack.push(i);
            } else if (bare.charAt(i) == close && !stack.isEmpty()) {
                pairs[stack.pop()] = i;
            }
        }
        stack.forEach(i -> pairs[i] = bare.length());
        return pairs;
    }

    /** The spans {start, end} of the top-level arguments of the call whose {@code (} is at {@code open}. */
    static List<int[]> arguments(String bare, int open) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i < bare.length(); i++) {
            char c = bare.charAt(i);
            if ("([{".indexOf(c) >= 0) {
                depth++;
            } else if (")]}".indexOf(c) >= 0 && depth-- == 0) {
                if (!out.isEmpty() || !bare.substring(start, i).isBlank()) {
                    out.add(new int[] {start, i});
                }
                return out;
            } else if (c == ',' && depth == 0) {
                out.add(new int[] {start, i});
                start = i + 1;
            }
        }
        out.add(new int[] {start, bare.length()});
        return out;
    }

    /** The end of the expression at {@code from}: a {@code ;} or {@code ,}, or a closer of its level. */
    static int expressionEnd(String bare, int from) {
        int depth = 0;
        for (int i = from; i < bare.length(); i++) {
            char c = bare.charAt(i);
            if ("([{".indexOf(c) >= 0) {
                depth++;
            } else if (")]}".indexOf(c) >= 0) {
                if (--depth < 0) {
                    return i;
                }
            } else if (depth == 0 && (c == ';' || c == ',')) {
                return i;
            }
        }
        return bare.length();
    }

    /** The 1-based line of {@code at}. */
    static int line(String source, int at) {
        int line = 1;
        for (int i = 0; i < at && i < source.length(); i++) {
            line += source.charAt(i) == '\n' ? 1 : 0;
        }
        return line;
    }
}
