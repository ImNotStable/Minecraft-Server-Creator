package io.github.imnotstable.msc.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the places in a test's source that reach repository files around its declared repoInputs sets (#370, #381).
 * Two kinds: a direct reach (a {@code ..} path, the working folder's parent, {@code user.dir}, {@code msc.repoRoot},
 * or a walk up chained onto a {@code RepoFiles} call), found textually; and a walk up from a variable that holds a
 * {@code RepoFiles} path, which follows Java scoping: only uses of that variable inside the block it is declared in
 * count, and a parameter, local or lambda parameter of the same name (or a reassignment) hides it.
 * <p>A source check, not a run-time one: tests hand {@code RepoFiles} paths to the code under test (the bench reads
 * CLAUDE.md under {@code RepoFiles.dir(set, "")}), Java 25 has no security manager to refuse a read, and a confining
 * Path of its own file system would break {@code toFile}, {@code toRealPath} and equality with ordinary paths.
 */
final class RepoBypasses {
    /** A reach around the sets: its line, the matched text and what to change. */
    record Bypass(int line, String text, String fix) {
        @Override
        public String toString() {
            return "line " + line + ": " + text.replaceAll("\\s+", " ") + " -- " + fix;
        }
    }

    private static final String ARGS = "(?:[^()]|\\([^()]*\\))*";
    /** One step up from a path: to its parent or a sibling, or through a {@code ..} segment. */
    private static final String WALK = "(?:\\s*\\.\\w+\\(" + ARGS + "\\))*?\\s*\\.(?:getParent\\(|getParentFile\\("
            + "|resolveSibling\\(|resolve\\(\"(?:[^\"]*/)?\\.\\.(?:/|\"))";
    private static final Pattern WALK_AT = Pattern.compile(WALK);
    private static final Pattern OUTSIDE = Pattern.compile(
            "(?:Path\\.of|Paths\\.get|new File)\\(\"\\.\\.(?:\"|/)"
            + "|(?:Path\\.of|Paths\\.get|new File)\\(\"\\.?\"\\)" + WALK
            + "|getProperty\\(\"user\\.dir\"\\)|\"msc\\.repoRoot\"");
    private static final Pattern CHAINED = Pattern.compile("RepoFiles\\.(?:path|dir)\\(" + ARGS + "\\)" + WALK);
    private static final Pattern DECLARED = Pattern.compile("(?<![\\w$.])([\\w$]+)\\s*=\\s*RepoFiles\\.(?:path|dir)\\(");
    private static final Pattern TYPE_BODY = Pattern.compile(
            "(?s).*(?:\\b(?:class|interface|enum|record)\\b[^;{}]*|\\bnew\\s+[\\w$.<>]+\\s*\\([^;{}]*\\)\\s*)$");
    private static final Pattern LAMBDA_PARAM = Pattern.compile("\\s*(?:,\\s*[\\w$]+\\s*)*\\)?\\s*->");
    private static final Pattern THROWS = Pattern.compile("\\s*throws\\s+[\\w$.,\\s<>]+?(?=[{;])");
    private static final Set<String> NOT_TYPES = Set.of("return", "throw", "new", "case", "else", "yield", "assert",
            "do", "instanceof");

    static final String OUTSIDE_FIX = "declare the file in a set in this module's build.gradle.kts "
            + "(repoInputs.set(\"name\", root.file(\"path/from/root\"))) and read it with RepoFiles.path(\"name\", "
            + "\"path/from/root\"), or RepoFiles.dir(\"name\", \"folder\") for a folder";
    static final String WALK_FIX = "ask for the file or folder you need by name instead of walking up: add it to the "
            + "set and use RepoFiles.path(set, file) or RepoFiles.dir(set, folder)";

    private RepoBypasses() {
    }

    /** The reaches around the declared sets in {@code source}, in source order per kind. */
    static List<Bypass> find(String source) {
        String code = mask(source, false);
        String bare = mask(source, true);
        int[] braces = pairs(bare, '{', '}');
        int[] parens = pairs(bare, '(', ')');
        List<Bypass> found = new ArrayList<>();
        OUTSIDE.matcher(code).results().forEach(m -> found.add(new Bypass(line(source, m.start()), m.group(),
                OUTSIDE_FIX)));
        CHAINED.matcher(code).results().forEach(m -> found.add(new Bypass(line(source, m.start()), m.group(),
                WALK_FIX)));
        Matcher d = DECLARED.matcher(bare);
        while (d.find()) {
            String var = d.group(1);
            int at = d.start(1);
            int block = enclosing(braces, at);
            int end = block < 0 ? bare.length() : braces[block];
            int start = block >= 0 && isDeclaration(bare, at) && TYPE_BODY.matcher(bare.substring(0, block)).matches()
                    ? block : at;
            List<int[]> hidden = new ArrayList<>();
            Matcher use = Pattern.compile("(?<![\\w$.])" + Pattern.quote(var) + "(?![\\w$])").matcher(bare)
                    .region(start, end).useTransparentBounds(true);
            List<Integer> uses = new ArrayList<>();
            while (use.find()) {
                int u = use.start();
                if (u == at) {
                    continue;
                }
                int[] h = hides(bare, braces, parens, u, var.length(), end);
                if (h != null) {
                    hidden.add(h);
                } else {
                    uses.add(u);
                }
            }
            for (int u : uses) {
                if (hidden.stream().anyMatch(h -> h[0] <= u && u < h[1])) {
                    continue;
                }
                Matcher w = WALK_AT.matcher(code).region(u + var.length(), code.length());
                if (w.lookingAt()) {
                    found.add(new Bypass(line(source, u), var + w.group(), WALK_FIX + " ('" + var + "' holds the "
                            + "RepoFiles path from line " + line(source, at) + "; if this '" + var + "' is another "
                            + "variable this check cannot tell apart, rename one of them)"));
                }
            }
        }
        return found;
    }

    /**
     * The region in which the occurrence at {@code u} hides the tracked variable: a declaration of the same name (a
     * parameter, local, pattern or lambda parameter) or a reassignment from something else; null if it hides nothing.
     */
    private static int[] hides(String bare, int[] braces, int[] parens, int u, int len, int scopeEnd) {
        int after = u + len;
        Matcher lambda = LAMBDA_PARAM.matcher(bare).region(after, bare.length());
        if (lambda.lookingAt()) {
            return new int[] {u, lambdaEnd(bare, braces, lambda.end())};
        }
        if (isDeclaration(bare, u)) {
            int block = enclosing(braces, u);
            int paren = enclosing(parens, u);
            if (paren >= 0 && paren > block) {
                return new int[] {u, bodyEnd(bare, braces, parens[paren] + 1)};
            }
            return new int[] {u, block < 0 ? bare.length() : braces[block]};
        }
        String rest = bare.substring(after);
        if (rest.matches("(?s)\\s*=(?!=).*") && !rest.matches("(?s)\\s*=\\s*RepoFiles\\..*")) {
            return new int[] {u, scopeEnd};
        }
        return null;
    }

    /** Whether the name at {@code u} is being declared: a type (or {@code var}) stands right before it. */
    private static boolean isDeclaration(String bare, int u) {
        int i = u - 1;
        while (i >= 0 && Character.isWhitespace(bare.charAt(i))) {
            i--;
        }
        if (i == u - 1 || i < 0) {
            return false;
        }
        char c = bare.charAt(i);
        if (c == ']' || c == '>' && (i == 0 || bare.charAt(i - 1) != '-') || c == '.' && bare.startsWith("...", i - 2)) {
            return followedByDeclarator(bare, u);
        }
        int w = i;
        while (w >= 0 && Character.isJavaIdentifierPart(bare.charAt(w))) {
            w--;
        }
        String word = bare.substring(w + 1, i + 1);
        return !word.isEmpty() && Character.isJavaIdentifierStart(word.charAt(0)) && !NOT_TYPES.contains(word)
                && followedByDeclarator(bare, u);
    }

    private static boolean followedByDeclarator(String bare, int u) {
        int i = u;
        while (i < bare.length() && Character.isJavaIdentifierPart(bare.charAt(i))) {
            i++;
        }
        String rest = bare.substring(i).stripLeading();
        return rest.isEmpty() || ";,):".indexOf(rest.charAt(0)) >= 0
                || rest.charAt(0) == '=' && !rest.startsWith("==");
    }

    /** Where the body that follows a parenthesised header at {@code from} ends: a block, a lambda or a statement. */
    private static int bodyEnd(String bare, int[] braces, int from) {
        Matcher t = THROWS.matcher(bare).region(from, bare.length());
        int i = t.lookingAt() ? t.end() : from;
        while (i < bare.length() && Character.isWhitespace(bare.charAt(i))) {
            i++;
        }
        if (i < bare.length() && bare.charAt(i) == '{') {
            return braces[i];
        }
        if (bare.startsWith("->", i)) {
            return lambdaEnd(bare, braces, i + 2);
        }
        return expressionEnd(bare, i, true);
    }

    private static int lambdaEnd(String bare, int[] braces, int from) {
        int i = from;
        while (i < bare.length() && Character.isWhitespace(bare.charAt(i))) {
            i++;
        }
        return i < bare.length() && bare.charAt(i) == '{' ? braces[i] : expressionEnd(bare, i, false);
    }

    /** The end of the expression or statement at {@code from}: a {@code ;}, or a {@code ,} or closer of its level. */
    private static int expressionEnd(String bare, int from, boolean statement) {
        int depth = 0;
        for (int i = from; i < bare.length(); i++) {
            char c = bare.charAt(i);
            if ("([{".indexOf(c) >= 0) {
                depth++;
            } else if (")]}".indexOf(c) >= 0) {
                if (--depth < 0) {
                    return i;
                }
            } else if (depth == 0 && (c == ';' || c == ',' && !statement)) {
                return i;
            }
        }
        return bare.length();
    }

    /** The opening index of the innermost pair that contains {@code at}, or -1. */
    private static int enclosing(int[] pairs, int at) {
        int best = -1;
        for (int open = 0; open < at; open++) {
            if (pairs[open] > at) {
                best = open;
            }
        }
        return best;
    }

    /** For each opening character, the index of its closing one (the text's end if unclosed); 0 elsewhere. */
    private static int[] pairs(String bare, char open, char close) {
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

    private static int line(String source, int at) {
        return (int) source.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
    }
}
