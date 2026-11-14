package io.github.imnotstable.msc.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds code that opens a Minecraft server's {@code server.properties} without msc-process's
 * {@code ServerPropertiesFile} (ADR 0033), for {@link ServerPropertiesAccessTest}.
 * <p>It follows the file, not string handling (#409): a path is server.properties when it is built from a literal that
 * names it ({@code dir.resolve("server.properties")}, {@code Path.of(...)}, {@code new File(...)}) or from
 * {@code ServerPropertiesFile.NAME}, or when a local, field, parameter or method result provably holds such a path.
 * Parameters are followed across the scanned classes: a call {@code PackProperties.write(file, ...)} with such a path
 * makes {@code write}'s first parameter one, in PackProperties.java, and so on to a fixed point (#410). Opening such a
 * path as text or a stream is reported: a read or write through {@code Files}, a {@code java.io} reader, writer or
 * stream (which is also how a {@code java.util.Properties} loads or stores it), or AtomicFile or OutputFile with a
 * writer that does one of those. Not reported: {@code ServerPropertiesFile} itself, existence and permission checks,
 * and byte-for-byte snapshots ({@code Files.readAllBytes}, {@code Files.write} of a {@code byte[]}, copy, move).
 * <p>A tripwire for ordinary code, not a proof against deliberate evasion (ADR 0035): a name built at run time, a path
 * passed through an instance method of another class, a collection or reflection is out of its reach.
 */
final class ServerPropertiesAccess {
    /** One opening of server.properties: the source, its line and the call. */
    record Finding(String file, int line, String call) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + call;
        }
    }

    /** The one class that may open the file. */
    static final String SHARED_CLASS = "ServerPropertiesFile";
    /** Classes that write whatever file they are given; what they are given is checked where they are called. */
    private static final Set<String> FILE_HELPERS = Set.of("AtomicFile", "OutputFile");

    private static final Pattern FILE_LITERAL = Pattern.compile("\"(?:[^\"\\s]*[/\\\\])?server\\.properties\"");
    private static final Pattern NAME_CONSTANT = Pattern.compile("(?:[\\w$.]*\\.)?ServerPropertiesFile\\s*\\.\\s*NAME");
    private static final Pattern IDENTIFIER = Pattern.compile("(?:this\\s*\\.\\s*|([\\w$]+)\\s*\\.\\s*)?([\\w$]+)");
    private static final Pattern CALL = Pattern.compile("(?<![\\w$])([\\w$]+)\\s*\\(");
    private static final Pattern ASSIGNMENT =
            Pattern.compile("(?<![\\w$.])(this\\s*\\.\\s*)?([\\w$]+)\\s*=(?!=)");
    private static final Pattern RETURN = Pattern.compile("(?<![\\w$.])return\\b");
    private static final Pattern LAMBDA = Pattern.compile("\\s*\\(?\\s*([\\w$]+)\\s*\\)?\\s*->.*", Pattern.DOTALL);
    /** A call that opens its first argument as text or a stream, or hands it to a file-writing helper. */
    private static final Pattern ACCESS = Pattern.compile("(?<![\\w$])(?:(?:java\\.nio\\.file\\.)?Files\\s*\\.\\s*"
            + "(readAllLines|readString|lines|newBufferedReader|newInputStream|newOutputStream|writeString"
            + "|newBufferedWriter|newByteChannel|write|readAllBytes)"
            + "|new\\s+(?:java\\.io\\.)?(FileReader|FileWriter|FileInputStream|FileOutputStream|PrintWriter"
            + "|RandomAccessFile|Scanner)"
            + "|(?:[\\w$]+\\.)*(AtomicFile|OutputFile)\\s*\\.\\s*(write|replace))\\s*\\(");
    /** Steps that keep a path pointing at the same file. */
    private static final Set<String> SAME_FILE = Set.of("toAbsolutePath", "normalize", "toRealPath", "toFile",
            "toPath", "getAbsoluteFile", "getCanonicalFile");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized", "try",
            "return", "new", "throw", "super", "this", "assert", "yield", "else", "do", "case");

    private record Method(String cls, String name, List<String> params, int from, int to) {
        String key() {
            return cls + "." + name;
        }
    }

    private record Unit(String file, String cls, String code, String bare, int[] parens, int[] parenOpen,
            List<Method> methods) {
    }

    private final List<Unit> units = new ArrayList<>();
    /** Parameters that hold the file, as {@code Class.method#index}. */
    private final Set<String> params = new HashSet<>();
    /** Methods that return the file, as {@code Class.method}. */
    private final Set<String> returns = new HashSet<>();
    /** Fields that hold the file, per class. */
    private final Map<String, Set<String>> fields = new HashMap<>();

    private ServerPropertiesAccess(Map<String, String> sources) {
        sources.forEach((file, text) -> units.add(unit(file, text)));
    }

    /** The openings of server.properties in {@code sources} (path to source text), in source order per file. */
    static List<Finding> find(Map<String, String> sources) {
        ServerPropertiesAccess a = new ServerPropertiesAccess(sources);
        a.follow();
        List<Finding> out = new ArrayList<>();
        for (Unit u : a.units) {
            if (!u.cls().equals(SHARED_CLASS)) {
                out.addAll(a.openings(u));
            }
        }
        return out;
    }

    private static Unit unit(String file, String text) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        String cls = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String code = SourceText.mask(text, false);
        String bare = SourceText.mask(text, true);
        int[] parens = SourceText.pairs(bare, '(', ')');
        int[] parenOpen = new int[bare.length() + 1];
        for (int i = 0; i < parens.length; i++) {
            if (bare.charAt(i) == '(') {
                parenOpen[Math.min(parens[i], bare.length())] = i;
            }
        }
        int[] braces = SourceText.pairs(bare, '{', '}');
        List<Method> methods = new ArrayList<>();
        Matcher m = CALL.matcher(bare);
        while (m.find()) {
            String n = m.group(1);
            int before = back(bare, m.start());
            if (KEYWORDS.contains(n) || before >= 0 && bare.charAt(before) == '.' || wordBefore(bare, m.start())
                    .matches("new|record|class|interface|enum")) {
                continue;
            }
            int open = m.end() - 1;
            int after = skip(bare, parens[open] + 1);
            if (bare.startsWith("throws", after)) {
                while (after < bare.length() && bare.charAt(after) != '{' && bare.charAt(after) != ';') {
                    after++;
                }
            }
            if (after < bare.length() && bare.charAt(after) == '{') {
                List<String> names = new ArrayList<>();
                for (int[] p : SourceText.arguments(bare, open)) {
                    Matcher last = Pattern.compile("([\\w$]+)\\s*(?:\\[\\s*])*\\s*$").matcher(bare.substring(p[0], p[1]));
                    names.add(last.find() ? last.group(1) : "");
                }
                methods.add(new Method(cls, n, names, after + 1, braces[after]));
            }
        }
        return new Unit(file, cls, code, bare, parens, parenOpen, methods);
    }

    /** Follows the file through locals, fields, parameters and returns until nothing more is learned. */
    private void follow() {
        boolean changed = true;
        for (int round = 0; changed && round < 100; round++) {
            changed = false;
            for (Unit u : units) {
                Set<String> f = fields.computeIfAbsent(u.cls(), k -> new HashSet<>());
                for (Matcher a = ASSIGNMENT.matcher(u.bare()); a.find(); ) {
                    if (outsideMethods(u, a.start()) && tainted(u, a.end(), SourceText.expressionEnd(u.bare(), a.end()),
                            f)) {
                        changed |= f.add(a.group(2));
                    }
                }
                for (Method m : u.methods()) {
                    Set<String> names = names(u, m);
                    for (Matcher a = ASSIGNMENT.matcher(u.bare()).region(m.from(), m.to()); a.find(); ) {
                        if (a.group(1) != null && tainted(u, a.end(), SourceText.expressionEnd(u.bare(), a.end()),
                                names)) {
                            changed |= f.add(a.group(2));
                        }
                    }
                    for (Matcher r = RETURN.matcher(u.bare()).region(m.from(), m.to()); r.find(); ) {
                        if (tainted(u, r.end(), SourceText.expressionEnd(u.bare(), r.end()), names)) {
                            changed |= returns.add(m.key());
                        }
                    }
                    for (Matcher c = CALL.matcher(u.bare()).region(m.from(), m.to()); c.find(); ) {
                        String target = callee(u, c.start(), c.group(1));
                        if (target == null) {
                            continue;
                        }
                        List<int[]> args = SourceText.arguments(u.bare(), c.end() - 1);
                        for (int i = 0; i < args.size(); i++) {
                            if (tainted(u, args.get(i)[0], args.get(i)[1], names)) {
                                changed |= params.add(target + "#" + i);
                            }
                        }
                    }
                }
            }
        }
    }

    /** The names that hold the file inside {@code m}: fields, parameters, locals and writer-lambda parameters. */
    private Set<String> names(Unit u, Method m) {
        Set<String> names = new HashSet<>(fields.getOrDefault(u.cls(), Set.of()));
        for (int i = 0; i < m.params().size(); i++) {
            if (params.contains(m.key() + "#" + i)) {
                names.add(m.params().get(i));
            } else {
                names.remove(m.params().get(i));
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Matcher a = ASSIGNMENT.matcher(u.bare()).region(m.from(), m.to()); a.find(); ) {
                if (!names.contains(a.group(2))
                        && tainted(u, a.end(), SourceText.expressionEnd(u.bare(), a.end()), names)) {
                    grew = names.add(a.group(2));
                }
            }
            for (Matcher c = ACCESS.matcher(u.bare()).region(m.from(), m.to()); c.find(); ) {
                List<int[]> args = SourceText.arguments(u.bare(), c.end() - 1);
                if (c.group(3) != null && args.size() == 2 && tainted(u, args.get(0)[0], args.get(0)[1], names)) {
                    Matcher lambda = LAMBDA.matcher(u.bare().substring(args.get(1)[0], args.get(1)[1]));
                    if (lambda.matches() && names.add(lambda.group(1))) {
                        grew = true;
                    }
                }
            }
        }
        return names;
    }

    /** The openings of the file in {@code u}, one per call. */
    private List<Finding> openings(Unit u) {
        Map<Integer, Finding> out = new LinkedHashMap<>();
        for (Method m : u.methods()) {
            Set<String> names = names(u, m);
            for (Matcher c = ACCESS.matcher(u.bare()).region(m.from(), m.to()); c.find(); ) {
                List<int[]> args = SourceText.arguments(u.bare(), c.end() - 1);
                if (args.isEmpty() || !tainted(u, args.get(0)[0], args.get(0)[1], names)) {
                    continue;
                }
                String call = opening(u, c, args);
                if (call != null) {
                    out.putIfAbsent(c.start(), new Finding(u.file(), SourceText.line(u.code(), c.start()),
                            call + " on server.properties"));
                }
            }
        }
        return List.copyOf(out.values());
    }

    /** What the access call {@code c} on the file does, or null when it is a byte-for-byte snapshot. */
    private static String opening(Unit u, Matcher c, List<int[]> args) {
        String bare = u.bare();
        if (c.group(1) != null) {
            String call = "Files." + c.group(1);
            if (c.group(1).equals("readAllBytes")) {
                return bare.substring(0, c.start()).matches("(?s).*\\bnew\\s+String\\s*\\(\\s*") ? call : null;
            }
            if (c.group(1).equals("write") && args.size() >= 2) {
                String data = bare.substring(args.get(1)[0], args.get(1)[1]).strip();
                boolean bytes = data.matches("[\\w$]+")
                        && Pattern.compile("\\bbyte\\s*\\[\\s*]\\s*" + Pattern.quote(data) + "\\b").matcher(bare).find();
                return bytes ? null : call;
            }
            return call;
        }
        if (c.group(2) != null) {
            return "new " + c.group(2);
        }
        boolean lambda = args.size() == 2 && LAMBDA.matcher(bare.substring(args.get(1)[0], args.get(1)[1])).matches();
        return lambda ? null : c.group(3) + "." + c.group(4) + " with a writer this check cannot see into";
    }

    /** The {@code Class.method} a call at {@code at} goes to when it is known, or null. */
    private String callee(Unit u, int at, String name) {
        if (KEYWORDS.contains(name)) {
            return null;
        }
        int before = back(u.bare(), at);
        String word = wordBefore(u.bare(), at);
        String cls;
        if (word.equals("new")) {
            cls = name;
        } else if (before >= 0 && u.bare().charAt(before) == '.') {
            String q = wordBefore(u.bare(), before);
            int qStart = back(u.bare(), before) - q.length() + 1;
            boolean chained = qStart > 0 && back(u.bare(), qStart) >= 0 && u.bare().charAt(back(u.bare(), qStart)) == '.';
            if (q.equals("this")) {
                cls = u.cls();
            } else if (!q.isEmpty() && Character.isUpperCase(q.charAt(0)) && !chained) {
                cls = q;
            } else {
                return null;
            }
        } else {
            cls = u.cls();
        }
        return FILE_HELPERS.contains(cls) || cls.equals(SHARED_CLASS) ? null : cls + "." + name;
    }

    /** Whether the expression in {@code [from, to)} is a path to server.properties. */
    private boolean tainted(Unit u, int from, int to, Set<String> names) {
        String bare = u.bare();
        int f = skip(bare, from);
        int t = to;
        while (t > f && Character.isWhitespace(bare.charAt(t - 1))) {
            t--;
        }
        if (f >= t) {
            return false;
        }
        String expr = bare.substring(f, t);
        if (FILE_LITERAL.matcher(u.code().substring(f, t)).matches() || NAME_CONSTANT.matcher(expr).matches()) {
            return true;
        }
        if (bare.charAt(f) == '(' && u.parens()[f] == t - 1) {
            return tainted(u, f + 1, t - 1, names);
        }
        List<int[]> parts = topLevelParts(bare, f, t);
        if (parts.size() > 1) {
            return parts.stream().anyMatch(p -> tainted(u, p[0], p[1], names));
        }
        if (bare.charAt(t - 1) == ')') {
            int open = u.parenOpen()[t - 1];
            String name = wordBefore(bare, open);
            if (name.isEmpty()) {
                return false;
            }
            int nameStart = back(bare, open) - name.length() + 1;
            int dot = back(bare, nameStart);
            List<int[]> args = SourceText.arguments(bare, open);
            boolean anyArg = args.stream().anyMatch(a -> tainted(u, a[0], a[1], names));
            if (wordBefore(bare, nameStart).equals("new")) {
                return name.equals("File") && anyArg;
            }
            if (dot >= f && bare.charAt(dot) == '.') {
                String receiver = bare.substring(f, dot).strip();
                if (SAME_FILE.contains(name)) {
                    return tainted(u, f, dot, names);
                }
                if (name.equals("resolve") || name.equals("resolveSibling") || receiver.matches("(?:java\\.nio\\.file\\.)?Paths?")
                        && (name.equals("of") || name.equals("get"))) {
                    return !args.isEmpty() && tainted(u, args.getLast()[0], args.getLast()[1], names);
                }
                String key = (receiver.equals("this") ? u.cls() : receiver) + "." + name;
                return returns.contains(key);
            }
            return returns.contains(u.cls() + "." + name);
        }
        Matcher id = IDENTIFIER.matcher(expr);
        if (id.matches()) {
            String q = id.group(1);
            return q == null ? names.contains(id.group(2))
                    : fields.getOrDefault(q, Set.of()).contains(id.group(2));
        }
        return false;
    }

    /** The operands of a top-level {@code ?:} or {@code +} in {@code [from, to)}, or the whole span. */
    private static List<int[]> topLevelParts(String bare, int from, int to) {
        List<int[]> parts = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = bare.charAt(i);
            if ("([{".indexOf(c) >= 0) {
                depth++;
            } else if (")]}".indexOf(c) >= 0) {
                depth--;
            } else if (depth == 0 && (c == '?' || c == ':' && !bare.startsWith("::", i) && !bare.startsWith("::", i - 1)
                    || c == '+')) {
                parts.add(new int[] {start, i});
                start = i + 1;
            }
        }
        parts.add(new int[] {start, to});
        return parts;
    }

    private static boolean outsideMethods(Unit u, int at) {
        return u.methods().stream().noneMatch(m -> m.from() <= at && at < m.to());
    }

    /** The index of the last non-space character before {@code at}, or -1. */
    private static int back(String bare, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(bare.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int skip(String bare, int at) {
        int i = at;
        while (i < bare.length() && Character.isWhitespace(bare.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The identifier that ends just before {@code at}, spaces skipped, or "". */
    private static String wordBefore(String bare, int at) {
        int end = back(bare, at) + 1;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(bare.charAt(start - 1))) {
            start--;
        }
        return bare.substring(start, end);
    }
}
