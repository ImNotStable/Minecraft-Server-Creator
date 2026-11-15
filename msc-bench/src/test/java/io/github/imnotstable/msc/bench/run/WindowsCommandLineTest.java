package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.check.Placeholders;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #624: every argument the bench starts a Windows program with reaches it whole. Runs on any OS: the encoded list is
 * joined as JDK 25's Windows {@code ProcessImpl} joins it in its default mode ({@link #jdkCommandLine}), then split as
 * the program does: by the C runtime's rules ({@link ProcessArguments#splitWindows}) for an .exe, and for a .cmd or
 * .bat launcher first by cmd.exe ({@link #throughScript}).
 */
class WindowsCommandLineTest {

    /** Arguments that break naive quoting, one per kind of trouble. */
    static Stream<String> nasty() {
        return Stream.of("", " ", "plain", "a b", "\"", "\"\"", "a\"b", "\"quoted\"", "say \"hi\" now", "\\", "a\\",
                "a b\\", "a b\\\\", "\\\"", "a\\\"b", "a\\\\\"b", "C:\\Program Files\\x\\", "\\\\server\\share",
                "tab\there", " leading", "trailing ", "-Dx=\"a b\"", "100%", "%PATH%", "%%", "%a%b%", "%PATH",
                "^", "a^b", "^\"", "x & calc", "a|b", "<in >out", "(paren)", ")", "!PATH!", "a!b", "\u00e9 \u2713",
                "\u00a0nbsp", "--flag=%TEMP%\\\"x\"^&y");
    }

    @ParameterizedTest
    @MethodSource("nasty")
    void anExeGetsEachArgumentWhole(String argument) {
        List<String> command = List.of("C:/tools/claude.exe", "-p", argument, "--model", "m");
        assertThat(throughExe(command)).containsExactlyElementsOf(command.subList(1, command.size()));
    }

    @ParameterizedTest
    @MethodSource("nasty")
    void aScriptGetsEachArgumentWhole(String argument) {
        List<String> command = List.of("C:/Users/me/AppData/Roaming/npm/claude.cmd", "-p", argument, "x y");
        assertThat(throughScript(command)).containsExactlyElementsOf(command.subList(1, command.size()));
    }

    @Test
    void allNastyArgumentsTogetherStayApart() {
        List<String> command = new ArrayList<>(List.of("C:/Program Files/Java/bin/java.exe"));
        command.addAll(nasty().toList());
        assertThat(throughExe(command)).containsExactlyElementsOf(command.subList(1, command.size()));
        command.set(0, "C:/Program Files (x86)/w/gradlew.bat");
        assertThat(throughScript(command)).containsExactlyElementsOf(command.subList(1, command.size()));
    }

    @Test
    void anExeGetsLineBreaksWhole() {
        List<String> command = List.of("claude", "-p", "line one\nline \"two\"\r\nend\\");
        assertThat(throughExe(command)).containsExactlyElementsOf(command.subList(1, command.size()));
    }

    /** The case of the issue: without the encoding, Sky Sprint's prompt reached claude.exe cut at "into Sky". */
    @Test
    void theJdkAloneSplitsAQuotedPrompt() {
        List<String> command = List.of("claude", "-p", "Turn it into \"Sky Sprint\", a race.", "--verbose");
        assertThat(ProcessArguments.splitWindows(jdkCommandLine(command))).hasSize(5);
        assertThat(throughExe(command)).containsExactly("-p", "Turn it into \"Sky Sprint\", a race.", "--verbose");
    }

    /**
     * Every shipped prompt, filled and with each preamble in front as a run puts it, reaches an .exe agent whole, and a
     * .cmd launcher too unless it has a line break: then it is refused by name instead of cut at the first one (so
     * the shipped config sends the prompt over stdin).
     */
    @Test
    void everyShippedPromptReachesTheAgentWhole() throws IOException {
        Path tasksDir = RepoFiles.dir("bundledTasks", "msc-bench/tasks");
        List<String> preambles = new ArrayList<>(List.of(""));
        try (Stream<Path> files = Files.list(tasksDir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("_preamble")).sorted().toList()) {
                preambles.add(Text.readOwn(file));
            }
        }
        assertThat(preambles).hasSizeGreaterThan(1);
        Map<String, String> values = BenchRunner.placeholders(BenchConfig.defaults(), Path.of("run/attempts/t/m-1"),
                Path.of("run/attempts/t/m-1/workspace"));
        List<TaskDef> tasks = TaskLoader.loadAll(tasksDir);
        assertThat(tasks).extracting(TaskDef::id).contains("game-sky-sprint");
        for (TaskDef task : tasks) {
            String body = Placeholders.fill(task.prompt(), values);
            for (String preamble : preambles) {
                String prompt = preamble.isEmpty() ? body : Placeholders.fill(preamble, values) + "\n\n" + body;
                List<String> command = List.of("claude", "-p", prompt, "--output-format", "stream-json");
                assertThat(throughExe(command)).as(task.id()).containsExactlyElementsOf(command.subList(1, 5));
                List<String> script = List.of("claude.cmd", "-p", prompt);
                if (prompt.contains("\n")) {
                    assertThatThrownBy(() -> WindowsCommandLine.forJdk(script, "true", null)).as(task.id())
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageStartingWith("cmd.exe ends a command at a line break, so claude.cmd cannot get");
                } else {
                    assertThat(throughScript(script)).as(task.id()).containsExactly("-p", prompt);
                }
            }
        }
    }

    @Test
    void anArgumentWithoutBlankOrQuoteIsLeftAsItIs() {
        List<String> command = List.of("java", "-Dfile.encoding=UTF-8", "C:\\dir\\", "-jar", "paper.jar");
        assertThat(WindowsCommandLine.forJdk(command, null, null)).isEqualTo(command);
    }

    @Test
    void aScriptRunsInCmdWithoutAutoRunOrDelayedExpansion() {
        assertThat(WindowsCommandLine.forJdk(List.of("gradlew.BAT", "build"), "true", "C:\\Windows\\cmd.exe"))
                .containsExactly("C:\\Windows\\cmd.exe", "/d", "/v:off", "/s", "/c", "\"\"gradlew.BAT\" ^^^\"build^^^\"\"");
        assertThat(WindowsCommandLine.forJdk(List.of("x.cmd"), "true", " ").getFirst()).isEqualTo("cmd.exe");
    }

    @Test
    void javasStrictQuotingIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> WindowsCommandLine.forJdk(List.of("claude", "x"), "FALSE", null))
                .hasMessageContaining("-Djdk.lang.Process.allowAmbiguousCommands=false is set");
    }

    @Test
    void prepareLeavesTheCommandAsItIsOffWindows() throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
        List<String> command = List.of("claude", "-p", "a \"b\"");
        assertThat(WindowsCommandLine.prepare(new ProcessBuilder(command)).command()).isEqualTo(command);
    }

    /** What an .exe started with {@code command} gets as its arguments (without the program). */
    private static List<String> throughExe(List<String> command) {
        List<String> parsed = ProcessArguments.splitWindows(jdkCommandLine(WindowsCommandLine.forJdk(command, "true",
                null)));
        return parsed.subList(1, parsed.size());
    }

    /**
     * What the real program behind a forwarding launcher started with {@code command} gets: cmd.exe reads the
     * {@code /s /c} line (variables, then carets and quotes), runs the script with the rest as {@code %*}, the
     * script's line {@code "node" "cli.js" %*} is read again (its {@code %*} is not expanded twice), and node splits
     * it by the C runtime's rules.
     */
    private static List<String> throughScript(List<String> command) {
        List<String> jdk = WindowsCommandLine.forJdk(command, "true", null);
        assertThat(jdk.subList(0, 5)).containsExactly("cmd.exe", "/d", "/v:off", "/s", "/c");
        assertThat(jdk).hasSize(6);
        String line = jdkCommandLine(jdk);
        assertThat(line).as("the JDK passes the /c text verbatim").endsWith(" /c " + jdk.get(5));
        String text = jdk.get(5);
        assertThat(text).startsWith("\"").endsWith("\"");
        text = text.substring(1, text.length() - 1);
        String first = cmdEscapes(expandVariables(text));
        assertThat(first).startsWith("\"" + command.getFirst() + "\"");
        String forwarded = first.substring(command.getFirst().length() + 2);
        String nodeLine = "node.exe" + cmdEscapes(forwarded);
        List<String> parsed = ProcessArguments.splitWindows(nodeLine);
        return parsed.subList(1, parsed.size());
    }

    /**
     * cmd.exe's first step on a command line: {@code %NAME%} of a defined variable becomes its value, anything else
     * stays as written (a command line, not a script, keeps an undefined one).
     */
    private static String expandVariables(String text) {
        Map<String, String> env = Map.of("PATH", "C:\\Windows", "TEMP", "C:\\Temp", "A", "<a>", "", "<empty>");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int close = c == '%' ? text.indexOf('%', i + 1) : -1;
            String name = close < 0 ? null : text.substring(i + 1, close).toUpperCase(Locale.ROOT);
            if (name != null && env.containsKey(name)) {
                out.append(env.get(name));
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * cmd.exe's second step: a quote opens or closes a quoted part and stays; outside one, {@code ^} makes the next
     * character plain, and an unescaped {@code & | < > ( )} would end or redirect the command, which fails the test.
     */
    private static String cmdEscapes(String text) {
        assertThat(text).doesNotContain("\n", "\r");
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                out.append(c);
            } else if (quoted) {
                out.append(c);
            } else if (c == '^') {
                assertThat(i + 1).as("a caret at the end continues the line").isLessThan(text.length());
                out.append(text.charAt(++i));
            } else {
                assertThat("&|<>()").as("unescaped cmd.exe metacharacter in " + text).doesNotContain(String.valueOf(c));
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The command line JDK 25's Windows {@code ProcessImpl} builds in its default (legacy) mode: the program, quoted
     * when it has a blank; each argument as it is when it is quoted already ({@code "..."}) or has no blank, else in
     * quotes with its trailing backslashes doubled and nothing inside escaped.
     */
    static String jdkCommandLine(List<String> command) {
        String program = command.getFirst();
        StringBuilder line = new StringBuilder(jdkNeedsQuotes(program) ? "\"" + program + "\"" : program);
        for (String argument : command.subList(1, command.size())) {
            line.append(' ');
            if (jdkNeedsQuotes(argument)) {
                int trailing = 0;
                while (trailing < argument.length() && argument.charAt(argument.length() - 1 - trailing) == '\\') {
                    trailing++;
                }
                line.append('"').append(argument).repeat('\\', trailing).append('"');
            } else {
                line.append(argument);
            }
        }
        return line.toString();
    }

    private static boolean jdkNeedsQuotes(String argument) {
        if (argument.isEmpty()) {
            return true;
        }
        if (argument.length() >= 2 && argument.startsWith("\"") && argument.endsWith("\"")) {
            return false;
        }
        return argument.chars().anyMatch(c -> Character.isSpaceChar(c) || Character.isWhitespace(c));
    }
}
