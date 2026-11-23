package io.github.imnotstable.msc.templates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Gradle commands a scaffolded project's CLAUDE.md and README.md tell you to run, and the tasks its
 * {@code build.gradle.kts} defines (#371). Shared by {@link TemplateDocsTest}, which reads the build file, and
 * {@link TemplateBuildTest}, which asks the project's own Gradle for its task list.
 */
final class TemplateDocs {
    static final List<String> DOCS = List.of("CLAUDE.md", "README.md", "RULES.md");

    private static final Pattern GRADLEW = Pattern.compile("\\./gradlew ([A-Za-z][\\w:]*)(?: --tests '\\*(\\w+)')?");
    /** A promise that typing {@code stop} in the terminal stops the run: "`stop` on the console", "type stop". */
    private static final Pattern STOP_ON_CONSOLE = Pattern.compile("`stop`|\\btype stop\\b", Pattern.CASE_INSENSITIVE);

    /** Tasks every Java project has (the java plugin, applied by every template's build). */
    private static final Set<String> JAVA_TASKS = Set.of("build", "test", "check", "assemble", "jar", "clean", "classes");
    /**
     * Run-config plugins whose run tasks forward this terminal's standard input to the game, checked with javap in
     * their jars: Loom 1.19 {@code RunGameTask} and ForgeGradle 7 {@code SlimeLauncherExec} call
     * {@code setStandardInput(System.in)}. ModDevGradle 2.0's {@code RunGameTask} never does, so a NeoForge build
     * sets it itself.
     */
    private static final Set<String> RUNS_FORWARD_STDIN = Set.of("fabric.loom", "forgegradle");
    private static final Set<String> RUN_CONFIG_PLUGINS = Set.of("fabric.loom", "forgegradle", "moddev");
    private static final Pattern PLUGIN_ALIAS = Pattern.compile("alias\\(libs\\.plugins\\.([\\w.]+)\\)");
    private static final Pattern RUN_CONFIG = Pattern.compile("(?:create|register|named)\\(\"(\\w+)\"\\)");
    private static final Pattern TASK_BLOCK = Pattern.compile(
            "tasks\\.(?:named(?:<\\w+>)?\\(\"(\\w+)\"\\)|register(?:<\\w+>)?\\(\"(\\w+)\"\\)|(\\w+))\\s*\\{");
    private static final String STDIN = "standardInput = System.`in`";

    private TemplateDocs() {
    }

    /** One documented {@code ./gradlew} command: its task, the test class of {@code --tests}, and the doc line. */
    record Command(String doc, String task, String testClass, boolean promisesStop, String line) {
    }

    /** What a build file defines: its task names, and the run tasks that forward standard input. */
    record Build(Set<String> tasks, Set<String> stdinTasks) {
    }

    static List<Command> commands(Path project) throws IOException {
        List<Command> out = new ArrayList<>();
        for (String doc : DOCS) {
            Path file = project.resolve(doc);
            if (!Files.exists(file)) {
                continue;
            }
            for (String line : Files.readAllLines(file)) {
                // A stop promise belongs to the command it follows: "build with X, run with Y (type stop)" is Y's.
                List<MatchResult> found = GRADLEW.matcher(line).results().toList();
                for (int i = 0; i < found.size(); i++) {
                    MatchResult m = found.get(i);
                    String part = line.substring(m.start(), i + 1 < found.size() ? found.get(i + 1).start() : line.length());
                    out.add(new Command(doc, m.group(1), m.group(2), STOP_ON_CONSOLE.matcher(part).find(), line.strip()));
                }
            }
        }
        return out;
    }

    static Build build(String script) {
        String plugins = block(script, script.indexOf("plugins {") + "plugins ".length());
        Set<String> aliases = new LinkedHashSet<>();
        PLUGIN_ALIAS.matcher(plugins).results().forEach(r -> aliases.add(r.group(1)));
        Set<String> tasks = new LinkedHashSet<>(JAVA_TASKS);
        Set<String> stdin = new LinkedHashSet<>();
        if (plugins.lines().anyMatch(l -> l.strip().equals("application"))) {
            tasks.add("run");
        }
        if (aliases.contains("run.paper")) {
            tasks.add("runServer"); // run-task 3.1 AbstractRun calls setStandardInput(System.in)
            stdin.add("runServer");
        }
        if (aliases.contains("run.velocity")) {
            tasks.add("runVelocity"); // the same run-task AbstractRun as run-paper
            stdin.add("runVelocity");
        }
        if (aliases.contains("run.waterfall")) {
            tasks.add("runWaterfall");
            stdin.add("runWaterfall");
        }
        for (String plugin : aliases) {
            if (!RUN_CONFIG_PLUGINS.contains(plugin)) {
                continue;
            }
            int runs = script.indexOf("runs {");
            String body = runs < 0 ? "" : block(script, runs + "runs ".length());
            Matcher m = RUN_CONFIG.matcher(body);
            while (m.find()) {
                String task = "run" + m.group(1).substring(0, 1).toUpperCase(Locale.ROOT) + m.group(1).substring(1);
                tasks.add(task);
                if (RUNS_FORWARD_STDIN.contains(plugin)) {
                    stdin.add(task);
                }
            }
        }
        Matcher m = TASK_BLOCK.matcher(script);
        while (m.find()) {
            String task = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (task.equals("withType")) {
                continue;
            }
            tasks.add(task);
            if (block(script, m.end() - 1).contains(STDIN)) {
                stdin.add(task);
            }
        }
        return new Build(Set.copyOf(tasks), Set.copyOf(stdin));
    }

    /** The text of the brace block that opens at {@code open} (the index of its '{'). */
    private static String block(String s, int open) {
        if (open < 0 || s.charAt(open) != '{') {
            return "";
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return s.substring(open + 1, i);
            }
        }
        throw new IllegalArgumentException("unclosed block at " + open);
    }
}
