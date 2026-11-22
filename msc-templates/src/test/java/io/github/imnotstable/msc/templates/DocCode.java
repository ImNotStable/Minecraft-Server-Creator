package io.github.imnotstable.msc.templates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The code a scaffolded project's CLAUDE.md and README.md show (#480): fenced {@code java} blocks, each a whole file
 * that {@link TemplateRecipesTest} adds to a scaffold and builds, and the Java names written in backticks in the text,
 * which {@link TemplateDocNamesTest} finds in code that the template builds compile.
 */
final class DocCode {
    private static final Pattern FENCE = Pattern.compile("^```(\\w*)\\n(.*?)^```$", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern SPAN = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TOP_TYPE = Pattern.compile(
            "^(?:public )?(?:final )?(?:abstract )?(?:class|record|interface|enum) (\\w+)", Pattern.MULTILINE);
    /** A dotted chain of identifiers, such as {@code BlockEvent.BreakEvent.BUS}, and whether a call follows it. */
    private static final Pattern CHAIN = Pattern.compile(
            "(?<![\\w$.])([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(\\()?");
    private static final Pattern FILE_NAME = Pattern.compile("[\\w.{}-]+\\.(?:java|ya?ml|toml|json|mcmeta|kts|txt|jar|properties|md)");
    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)'");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");

    private DocCode() {
    }

    /** A fenced {@code java} block of {@code doc}. */
    record Block(String doc, String code) {
        /** Where the file goes in the project: its package and type, under src/test for a class named *Test. */
        String path() {
            Matcher pkg = PACKAGE.matcher(code);
            Matcher type = TOP_TYPE.matcher(code);
            if (!pkg.find() || !type.find()) {
                throw new IllegalStateException(doc + ": a java block must be a whole file (package and type):\n" + code);
            }
            String dir = type.group(1).endsWith("Test") ? "src/test/java/" : "src/main/java/";
            return dir + pkg.group(1).replace('.', '/') + "/" + type.group(1) + ".java";
        }
    }

    static List<Block> javaBlocks(Path project) throws IOException {
        List<Block> out = new ArrayList<>();
        for (String doc : TemplateDocs.DOCS) {
            Path file = project.resolve(doc);
            if (Files.exists(file)) {
                Matcher m = FENCE.matcher(Files.readString(file));
                while (m.find()) {
                    if (m.group(1).equals("java")) {
                        out.add(new Block(doc, m.group(2)));
                    }
                }
            }
        }
        return out;
    }

    /** The inline code spans of a Markdown text, outside fenced blocks. */
    static List<String> spans(String markdown) {
        return SPAN.matcher(FENCE.matcher(markdown).replaceAll("")).results().map(r -> r.group(1)).toList();
    }

    /**
     * The Java names a span asks the reader to write: every identifier of a dotted chain that has a capital letter or
     * is called, except a lowercase first part (a variable such as {@code source} or a package root). Paths, file
     * names and console lines name nothing.
     */
    static Set<String> names(String span) {
        Set<String> out = new LinkedHashSet<>();
        if (span.contains("/") && !span.contains("(") || FILE_NAME.matcher(span).matches()) {
            return out;
        }
        Matcher m = CHAIN.matcher(STRING.matcher(span).replaceAll("\"\""));
        while (m.find()) {
            String[] parts = m.group(1).split("\\.");
            boolean called = m.group(2) != null;
            if (!called && m.group(1).chars().noneMatch(Character::isUpperCase)) {
                continue;
            }
            for (int i = 0; i < parts.length; i++) {
                boolean variable = i == 0 && parts.length > 1 && parts[0].chars().noneMatch(Character::isUpperCase);
                if (!variable) {
                    out.add(parts[i]);
                }
            }
        }
        return out;
    }

    /** The identifiers of Java or Kotlin code, without comments and string literals. */
    static Set<String> words(String code) {
        Set<String> out = new LinkedHashSet<>();
        WORD.matcher(STRING.matcher(COMMENT.matcher(code).replaceAll(" ")).replaceAll(" ")).results()
                .forEach(r -> out.add(r.group()));
        return out;
    }

    /** The identifiers of every .java and .kts file in a project. */
    static Set<String> projectWords(Path project) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(project)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kts")).toList()) {
                out.addAll(words(Files.readString(p)));
            }
        }
        return out;
    }
}
