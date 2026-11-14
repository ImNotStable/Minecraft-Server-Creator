package io.github.imnotstable.msc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the places in a test's source that reach repository files around its declared repoInputs sets (#370, #381,
 * #410). Three kinds: a direct reach ({@code user.dir}, {@code msc.repoRoot}, or a walk up chained onto a
 * {@code RepoFiles} call), found textually; a path built from a literal ({@code Path.of}, {@code Paths.get},
 * {@code new File}) whose chain of {@code getParent}, {@code resolveSibling} and {@code resolve("..")} steps climbs
 * above the folder the literal starts in, the module's own folder for a test; and a walk up from a variable that holds
 * a {@code RepoFiles} path or such a literal path, which follows Java scoping: only uses of that variable inside the
 * block it is declared in count, and a parameter, local or lambda parameter of the same name (or a reassignment, after
 * its value) hides it.
 * <p>A source check, not a run-time one: tests hand {@code RepoFiles} paths to the code under test (the bench reads
 * CLAUDE.md under {@code RepoFiles.dir(set, "")}), Java 25 has no security manager to refuse a read, and a confining
 * Path of its own file system would break {@code toFile}, {@code toRealPath} and equality with ordinary paths. A
 * tripwire for ordinary code, not a proof against deliberate evasion (ADR 0035, ADR 0036): a path name built at run
 * time, a variable copied into another, a loop counted beyond its first step, or reflection is out of its reach. It is
 * not to grow further (ADR 0036): a new shape it misses is a review comment, not another rule here.
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
    private static final Pattern OUTSIDE = Pattern.compile("getProperty\\(\"user\\.dir\"\\)|\"msc\\.repoRoot\"");
    private static final Pattern CHAINED = Pattern.compile("RepoFiles\\.(?:path|dir)\\(" + ARGS + "\\)" + WALK);
    /**
     * A path built from the working folder: the start of a {@code Path.of}, {@code Paths.get} or {@code new File},
     * also fully qualified ({@code java.nio.file.Path.of}, {@code new java.io.File}).
     */
    private static final String LITERAL_PATH = "(?:(?:java\\.nio\\.file\\.)?(?:Path\\s*\\.\\s*of|Paths\\s*\\.\\s*get)"
            + "|new\\s+(?:java\\.io\\.)?File)\\s*\\(";
    private static final Pattern LITERAL_START = Pattern.compile("(?<![\\w$.])" + LITERAL_PATH);
    private static final Pattern DECLARED = Pattern.compile(
            "(?<![\\w$.])([\\w$]+)\\s*=\\s*(?:RepoFiles\\.(?:path|dir)\\(|" + LITERAL_PATH + ")");
    private static final Pattern REASSIGNED_FROM_SEED = Pattern.compile(
            "(?s)\\s*=\\s*(?:RepoFiles\\.|" + LITERAL_PATH + ").*");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    /** Steps that keep a path where it is. */
    private static final Set<String> STAY = Set.of("toAbsolutePath", "getAbsoluteFile", "normalize", "toRealPath",
            "getCanonicalFile", "toFile", "toPath");
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
        String code = SourceText.mask(source, false);
        String bare = SourceText.mask(source, true);
        int[] braces = SourceText.pairs(bare, '{', '}');
        int[] parens = SourceText.pairs(bare, '(', ')');
        List<Bypass> found = new ArrayList<>();
        OUTSIDE.matcher(code).results().forEach(m -> found.add(new Bypass(line(source, m.start()), m.group(),
                OUTSIDE_FIX)));
        Matcher literal = LITERAL_START.matcher(bare);
        while (literal.find()) {
            Walk w = literalPath(code, bare, parens, literal.end() - 1);
            if (w.out() >= 0) {
                found.add(new Bypass(line(source, literal.start()), code.substring(literal.start(), w.out()),
                        OUTSIDE_FIX));
            }
        }
        CHAINED.matcher(code).results().forEach(m -> found.add(new Bypass(line(source, m.start()), m.group(),
                WALK_FIX)));
        Matcher d = DECLARED.matcher(bare);
        while (d.find()) {
            String var = d.group(1);
            int at = d.start(1);
            boolean repoFiles = bare.substring(d.end(1), d.end()).contains("RepoFiles");
            Walk rhs = repoFiles ? null : literalPath(code, bare, parens, d.end() - 1);
            if (rhs != null && (rhs.depth() == Walk.UNKNOWN || rhs.out() >= 0
                    || !bare.substring(rhs.end()).matches("(?s)\\s*[;,)].*"))) {
                continue; // not a path the variable holds, or already reported where it is built
            }
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
                String held = repoFiles ? "RepoFiles path" : "working-folder path";
                String text = null;
                if (repoFiles) {
                    Matcher w = WALK_AT.matcher(code).region(u + var.length(), code.length());
                    text = w.lookingAt() ? var + w.group() : null;
                } else {
                    Walk w = walk(code, bare, parens, u + var.length(), rhs.depth());
                    text = w.out() >= 0 ? code.substring(u, w.out()) : null;
                }
                if (text != null) {
                    found.add(new Bypass(line(source, u), text, WALK_FIX + " ('" + var + "' holds the " + held
                            + " from line " + line(source, at) + "; if this '" + var + "' is another variable this "
                            + "check cannot tell apart, rename one of them)"));
                }
            }
        }
        return found;
    }

    /**
     * How far a chain of path steps went: {@code depth} folders below where it started ({@link #UNKNOWN} when a step
     * is not a path step), {@code end} where the chain stopped, and {@code out} the end of the step that climbed above
     * the start, or -1.
     */
    private record Walk(int depth, int end, int out) {
        static final int UNKNOWN = Integer.MIN_VALUE;
    }

    /** The walk of the literal path whose {@code (} is at {@code open}: its own segments, then its chain. */
    private static Walk literalPath(String code, String bare, int[] parens, int open) {
        int close = parens[open];
        int depth = 0;
        for (int[] arg : SourceText.arguments(bare, open)) {
            Matcher s = STRING.matcher(code.substring(arg[0], arg[1]).strip());
            if (!s.matches()) {
                return new Walk(Walk.UNKNOWN, close, -1);
            }
            depth = descend(depth, s.group(1));
            if (depth < 0) {
                return new Walk(depth, close + 1, Math.min(close + 1, code.length()));
            }
        }
        return walk(code, bare, parens, close + 1, depth);
    }

    /** The walk of the chain of calls at {@code from} on a path {@code depth} folders below the start. */
    private static Walk walk(String code, String bare, int[] parens, int from, int depth) {
        int i = from;
        int d = depth;
        while (true) {
            int dot = skipSpace(bare, i);
            if (dot >= bare.length() || bare.charAt(dot) != '.') {
                return new Walk(d, i, -1);
            }
            int nameAt = skipSpace(bare, dot + 1);
            int nameEnd = nameAt;
            while (nameEnd < bare.length() && Character.isJavaIdentifierPart(bare.charAt(nameEnd))) {
                nameEnd++;
            }
            int open = skipSpace(bare, nameEnd);
            if (nameEnd == nameAt || open >= bare.length() || bare.charAt(open) != '(') {
                return new Walk(d, i, -1);
            }
            String name = bare.substring(nameAt, nameEnd);
            int close = parens[open];
            int next = Math.min(close + 1, bare.length());
            List<int[]> args = SourceText.arguments(bare, open);
            if (name.equals("getParent") || name.equals("getParentFile") || name.equals("resolveSibling")) {
                d--;
                if (d < 0) {
                    return new Walk(d, next, next);
                }
                d += name.equals("resolveSibling") ? 1 : 0;
            } else if (name.equals("resolve") && args.size() == 1) {
                Matcher s = STRING.matcher(code.substring(args.get(0)[0], args.get(0)[1]).strip());
                d = s.matches() ? descend(d, s.group(1)) : d + 1;
                if (d < 0) {
                    return new Walk(d, next, next);
                }
            } else if (!STAY.contains(name)) {
                return new Walk(Walk.UNKNOWN, i, -1);
            }
            i = next;
        }
    }

    /** {@code depth} after the segments of {@code path}, or the first negative depth a {@code ..} reaches. */
    private static int descend(int depth, String path) {
        int d = depth;
        for (String segment : path.split("[/\\\\]")) {
            if (segment.equals("..")) {
                if (--d < 0) {
                    return d;
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                d++;
            }
        }
        return d;
    }

    private static int skipSpace(String bare, int from) {
        int i = from;
        while (i < bare.length() && Character.isWhitespace(bare.charAt(i))) {
            i++;
        }
        return i;
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
        if (rest.matches("(?s)\\s*=(?!=).*") && !REASSIGNED_FROM_SEED.matcher(rest).matches()) {
            // From the end of the new value on: the value itself still reads the old one (p = p.getParent()).
            int value = after + rest.indexOf('=') + 1;
            return new int[] {expressionEnd(bare, value, true), scopeEnd};
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

    private static int line(String source, int at) {
        return SourceText.line(source, at);
    }
}
