package io.github.imnotstable.msc.core.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A project's own Java source as api_search {@code project=} sees it (ADR 0063): its types and methods by name, each
 * with {@code path:line} and the test lines that call it, so an agent opens a few places instead of every file.
 * Read fresh on each call: a project is small and its source changes between calls.
 */
public final class ProjectSource {
    /** A type or method declaration: modifiers, an optional type, then the name and an opening parenthesis. */
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\\s+)*"
                    + "(?:<[^>]+>\\s+)?(?:[\\w.]+(?:<.*>)?(?:\\[\\])*\\s+)?(\\w+)\\s*\\(");
    private static final Pattern TYPE = Pattern.compile("\\b(class|record|interface|enum)\\s+(\\w+)");
    /** Words a {@link #METHOD} match may start with that make it a statement, not a declaration. */
    private static final Set<String> NOT_NAMES = Set.of("if", "for", "while", "switch", "catch", "return", "new", "throw",
            "else", "try", "synchronized", "super", "this", "assert", "case", "yield");
    /** Most test lines shown per declaration. */
    private static final int MAX_TESTS = 3;

    private record Decl(String file, int line, String name, String text) {
    }

    private record Hit(int score, Decl decl) {
    }

    private ProjectSource() {
    }

    /**
     * Declarations under {@code project}'s {@code src/main} whose name has every word (else the most words, said so),
     * best first, each with up to {@value #MAX_TESTS} {@code src/test} lines that use the name.
     */
    public static String search(Path project, String query, int limit) throws IOException {
        List<Path> main = javaFiles(project.resolve("src/main"));
        if (main.isEmpty()) {
            return "no Java source under " + project.resolve("src/main");
        }
        List<Decl> decls = new ArrayList<>();
        for (Path f : main) {
            decls.addAll(declarations(project, f));
        }
        String[] words = Stream.of(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+"))
                .filter(w -> w.length() > 1).toArray(String[]::new);
        List<Hit> hits = hits(decls, words, true);
        String head = "";
        if (hits.isEmpty()) {
            hits = hits(decls, words, false);
            head = hits.isEmpty() ? "" : "no name has all of \"" + query + "\"; closest, most words first:\n";
        }
        if (hits.isEmpty()) {
            return "no declarations match \"" + query + "\" in " + main.size() + " source files";
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(h -> h.decl().file()).thenComparingInt(h -> h.decl().line()));
        List<Path> tests = javaFiles(project.resolve("src/test"));
        StringBuilder out = new StringBuilder(head);
        for (Hit h : hits.subList(0, Math.min(limit, hits.size()))) {
            Decl d = h.decl();
            out.append(d.file()).append(':').append(d.line()).append(' ').append(d.text());
            List<String> used = usedIn(project, tests, d.name());
            if (!used.isEmpty()) {
                out.append("  tests: ").append(String.join(", ", used));
            }
            out.append('\n');
        }
        if (hits.size() > limit) {
            out.append('[').append(hits.size() - limit).append(" more; add words or raise limit]");
        }
        return out.toString().stripTrailing();
    }

    private static List<Hit> hits(List<Decl> decls, String[] words, boolean every) {
        List<Hit> hits = new ArrayList<>();
        for (Decl d : decls) {
            // The file name counts too: "progress save" finds Progress.save.
            String name = d.name().toLowerCase(Locale.ROOT);
            String context = (d.file() + " " + d.text()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String w : words) {
                int s = name.equals(w) ? 400 : name.contains(w) ? 200 : context.contains(w) ? 50 : 0;
                if (s == 0 && every) {
                    score = 0;
                    break;
                }
                score += s == 0 || every ? s : s + 1000;
            }
            if (score > 0) {
                hits.add(new Hit(score, d));
            }
        }
        return hits;
    }

    static List<Decl> declarations(Path project, Path file) throws IOException {
        String shown = project.relativize(file).toString().replace('\\', '/');
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Decl> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            Matcher type = TYPE.matcher(line);
            if (type.find() && !trimmed.startsWith("return") && !line.contains("\"")) {
                out.add(new Decl(shown, i + 1, type.group(2), signature(trimmed)));
                continue;
            }
            Matcher m = METHOD.matcher(line);
            // A declaration ends its line with a body or a throws clause, not a statement's ";".
            if (m.find() && !NOT_NAMES.contains(m.group(1)) && !trimmed.endsWith(";") && !trimmed.contains(" = ")
                    && !trimmed.startsWith("return ") && !trimmed.startsWith("new ") && !trimmed.startsWith(".")) {
                out.add(new Decl(shown, i + 1, m.group(1), signature(trimmed)));
            }
        }
        return out;
    }

    /** The declaration without its body or annotations, kept short. */
    private static String signature(String trimmed) {
        String s = trimmed.replaceAll("\\s*\\{.*$", "").replaceAll("^(@\\w+(\\([^)]*\\))?\\s+)+", "");
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    /** {@code Test.java:line} of the first test lines that call or name {@code name}. */
    private static List<String> usedIn(Path project, List<Path> tests, String name) throws IOException {
        Pattern use = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        List<String> found = new ArrayList<>();
        for (Path t : tests) {
            List<String> lines = Files.readAllLines(t, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && found.size() < MAX_TESTS; i++) {
                if (use.matcher(lines.get(i)).find()) {
                    found.add(t.getFileName() + ":" + (i + 1));
                    break;
                }
            }
        }
        return found;
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).sorted().toList();
        }
    }
}
