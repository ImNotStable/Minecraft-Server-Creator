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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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
                "\u00a0nbsp", "--flag=%TEMP%\\\"x\"^&y", "%PATH:a=b%", "-Pdir=%TEMP:\\=/%", "%TEMP:~0,2%",
                "%PATH:~-3%", "%PATH:*\\=x%", "%A:~1%", "%A", "a%A%b", "%%A%%", "%A%A%", "%TEMP:~0,2%PATH%",
                "%:%", "%A:%", "%^A%", "%^^^A%", "100%:x=y%", "%~1", "%*");
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

    /**
     * #630: the encoder before the fix, which escaped a {@code %} but not the character after it, let cmd.exe expand
     * {@code %NAME:old=new%} and {@code %NAME:~start,length%}, since the name ends at the {@code :}. The model catches
     * it, with only the issue's ordinary variables defined; the fixed encoder passes the same arguments whole. Only the
     * search and replace form shows it here: the old encoder put {@code ^^^} before the closing {@code %}, so a
     * substring's bound ended in carets, and how cmd.exe reads such a number is not modelled.
     */
    @ParameterizedTest
    @ValueSource(strings = {"-Pdir=%TEMP:\\=/%", "%PATH:a=b%", "%PATH:*\\=x%", "%Path:WINDOWS=x%"})
    void theOldEncoderLetAColonFormExpand(String argument) {
        Function<String, String> env = Map.of("PATH", "C:\\Windows", "TEMP", "C:\\Temp")::get;
        String script = "C:/ws/gradlew.bat";
        String oldText = "\"" + script + "\" "
                + oldEscapeForCmd(oldEscapeForCmd(WindowsCommandLine.quoteAlways(argument)));
        assertThat(throughScriptText(script, oldText, env)).hasSize(1).first().isNotEqualTo(argument);
        assertThat(throughScript(List.of(script, argument), env)).containsExactly(argument);
    }

    /** The issue's trace: TEMP's backslashes became slashes, PATH came through as written. */
    @Test
    void theIssuesCommandReachesGradleAsWritten() {
        List<String> command = List.of("C:/ws/gradlew.bat", "-Pdir=%TEMP:\\=/%", "%PATH%");
        Function<String, String> env = Map.of("PATH", "C:\\Windows", "TEMP", "C:\\Users\\me\\Temp")::get;
        assertThat(throughScript(command, env)).containsExactly("-Pdir=%TEMP:\\=/%", "%PATH%");
        String oldText = "\"C:/ws/gradlew.bat\" " + oldEscapeForCmd(oldEscapeForCmd("\"-Pdir=%TEMP:\\=/%\""));
        assertThat(throughScriptText("C:/ws/gradlew.bat", oldText, env)).containsExactly("-Pdir=C:/Users/me/Temp");
    }

    @Test
    void everyPercentIsFollowedByACaretTwiceOver() {
        assertThat(WindowsCommandLine.escapeForCmd(WindowsCommandLine.escapeForCmd("%PATH:a=b%")))
                .isEqualTo("^^^%^^PATH:a=b^^^%");
        assertThat(WindowsCommandLine.escapeForCmd(WindowsCommandLine.escapeForCmd("\"%%\"")))
                .isEqualTo("^^^\"^^^%^^^%^^^\"");
    }

    /** cmd.exe could look up a name starting with ^ after a %, so a script's cmd.exe gets no such variable. */
    @Test
    void aScriptsEnvironmentLosesVariablesNamedWithACaret() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "p", "^^^A", "x", "^", "y", "A^", "z"));
        WindowsCommandLine.dropCaretVariables(env);
        assertThat(env).containsOnlyKeys("PATH", "A^");
    }

    /** The encoder before #630: a caret before each metacharacter only. */
    private static String oldEscapeForCmd(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\"%!^&|<>()".indexOf(c) >= 0) {
                out.append('^');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** What an .exe started with {@code command} gets as its arguments (without the program). */
    private static List<String> throughExe(List<String> command) {
        List<String> parsed = ProcessArguments.splitWindows(jdkCommandLine(WindowsCommandLine.forJdk(command, "true",
                null)));
        return parsed.subList(1, parsed.size());
    }

    /** {@link #throughScript(List, Function)} in the worst environment, {@link #EVERY_NAME_BUT_CARET}. */
    private static List<String> throughScript(List<String> command) {
        return throughScript(command, EVERY_NAME_BUT_CARET);
    }

    /**
     * The worst environment a script's cmd.exe can have: every variable is defined, except a name starting with
     * {@code ^}, which {@link WindowsCommandLine#prepare} removes from a script's environment and cmd.exe never defines
     * itself. Each value is the name in angle brackets.
     */
    private static final Function<String, String> EVERY_NAME_BUT_CARET = name -> name.startsWith("^") ? null
            : "<" + name + ">";

    /**
     * What the real program behind a forwarding launcher started with {@code command} gets, with {@code env} giving
     * each defined variable's value by its upper-case name (null when undefined).
     */
    private static List<String> throughScript(List<String> command, Function<String, String> env) {
        List<String> jdk = WindowsCommandLine.forJdk(command, "true", null);
        assertThat(jdk.subList(0, 5)).containsExactly("cmd.exe", "/d", "/v:off", "/s", "/c");
        assertThat(jdk).hasSize(6);
        String line = jdkCommandLine(jdk);
        assertThat(line).as("the JDK passes the /c text verbatim").endsWith(" /c " + jdk.get(5));
        String text = jdk.get(5);
        assertThat(text).startsWith("\"").endsWith("\"");
        return throughScriptText(command.getFirst(), text.substring(1, text.length() - 1), env);
    }

    /**
     * What the real program gets from the {@code /s /c} text {@code text}, its outer quotes already taken off: cmd.exe
     * reads it (variables, then carets and quotes), runs the script with the rest as {@code %*}, the script's line
     * {@code "node" "cli.js" %*} is read again for carets and quotes (cmd.exe expands {@code %*} there but does not
     * look for variables again in the text {@code %*} puts in), and node splits it by the C runtime's rules.
     */
    private static List<String> throughScriptText(String script, String text, Function<String, String> env) {
        String first = cmdEscapes(expandVariables(text, env));
        assertThat(first).startsWith("\"" + script + "\"");
        String forwarded = first.substring(script.length() + 2);
        String nodeLine = "node.exe" + cmdEscapes(forwarded);
        List<String> parsed = ProcessArguments.splitWindows(nodeLine);
        return parsed.subList(1, parsed.size());
    }

    /**
     * cmd.exe's first step on a command line, before any caret or quote is read, by the rules {@code set /?} documents
     * (a command line, not a script: there is no {@code %*}, {@code %1}, {@code %~1} and no {@code %%} escape):
     * <ul>
     *   <li>from a {@code %} to the next {@code %} is one expression; the variable name is its text up to the first
     *       {@code :}, or all of it, matched without regard to case;</li>
     *   <li>{@code %NAME%} becomes the value;</li>
     *   <li>{@code %NAME:~start%} and {@code %NAME:~start,length%} the substring, a negative start counting from the
     *       end and a negative length leaving that many characters off the end;</li>
     *   <li>{@code %NAME:old=new%} the value with every {@code old} replaced by {@code new}, ignoring case, and
     *       {@code %NAME:*old=new%} with everything up to and including the first {@code old} replaced;</li>
     *   <li>an undefined name, or a {@code %} with no second one, leaves the {@code %} as written (on a command line,
     *       unlike in a script).</li>
     * </ul>
     * Not modelled, and failing the test if reached with a defined name: a substring bound that is not a plain integer,
     * a {@code :} form with no {@code =} or an empty {@code old}, and the hidden {@code =C:} names, whose {@code :} is
     * part of the name. Where cmd.exe resumes after an undefined name (after its opening or at its closing {@code %})
     * is not pinned down either: the model resumes right after the opening one, so it tries every {@code %} as the
     * start of an expression, which covers both. Delayed {@code !NAME!} expansion is off ({@code /v:off}).
     */
    private static String expandVariables(String text, Function<String, String> env) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int close = c == '%' ? text.indexOf('%', i + 1) : -1;
            String body = close < 0 ? null : text.substring(i + 1, close);
            int colon = body == null ? -1 : body.indexOf(':');
            String value = body == null ? null
                    : env.apply((colon < 0 ? body : body.substring(0, colon)).toUpperCase(Locale.ROOT));
            if (value == null) {
                out.append(c);
                i++;
            } else {
                out.append(colon < 0 ? value : modify(value, body.substring(colon + 1), text));
                i = close + 1;
            }
        }
        return out.toString();
    }

    /** {@code value} after the text following the {@code :} of {@code %NAME:...%}, as {@code set /?} describes it. */
    private static String modify(String value, String modifier, String text) {
        if (modifier.startsWith("~")) {
            Matcher m = Pattern.compile("(-?\\d+)(?:,(-?\\d+))?").matcher(modifier.substring(1));
            assertThat(m.matches()).as("substring form not modelled: %s in %s", modifier, text).isTrue();
            int length = value.length();
            int start = Integer.parseInt(m.group(1));
            start = Math.clamp(start < 0 ? length + start : start, 0, length);
            int end = length;
            if (m.group(2) != null) {
                int count = Integer.parseInt(m.group(2));
                end = count < 0 ? length + count : start + count;
            }
            return value.substring(start, Math.clamp(end, start, length));
        }
        int equals = modifier.indexOf('=');
        String old = equals < 0 ? "" : modifier.substring(modifier.startsWith("*") ? 1 : 0, equals);
        assertThat(old).as("search and replace form not modelled: %s in %s", modifier, text).isNotEmpty();
        String replacement = modifier.substring(equals + 1);
        String lower = value.toLowerCase(Locale.ROOT);
        String oldLower = old.toLowerCase(Locale.ROOT);
        if (modifier.startsWith("*")) {
            int at = lower.indexOf(oldLower);
            return at < 0 ? value : replacement + value.substring(at + old.length());
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int at = lower.indexOf(oldLower); at >= 0; at = lower.indexOf(oldLower, from)) {
            out.append(value, from, at).append(replacement);
            from = at + old.length();
        }
        return out.append(value.substring(from)).toString();
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
