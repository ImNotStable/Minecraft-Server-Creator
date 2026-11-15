package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one place the bench turns a command into what Java needs on Windows, so every argument reaches the program
 * whole, quotes, backslashes, spaces and all (#624). Every process the bench starts goes through {@link #prepare}
 * ({@link ChildProcesses#start}, a check's default start): the agent, the server and every check command.
 *
 * <p>Linux and macOS hand a program its arguments as a list, so nothing changes there. Windows hands it one command
 * line, which the program splits again. In its default mode the JDK joins the arguments without escaping a {@code "}
 * inside one (its {@code VERIFICATION_LEGACY}): an argument with spaces is wrapped in quotes as it is, and one that
 * already starts and ends with a quote, or has no blank, goes in verbatim. So a prompt holding {@code "Sky Sprint"}
 * reached {@code claude.exe} as several arguments. This class therefore encodes each argument itself, by the
 * C runtime's rules ({@code CommandLineToArgvW}): an argument with no blank and no quote stays as it is; any other is
 * wrapped in quotes, with {@code 2n+1} backslashes before an inner quote and {@code 2n} before the closing one. The
 * result starts and ends with a quote, so the JDK passes it on untouched. The JDK's strict mode
 * ({@code -Djdk.lang.Process.allowAmbiguousCommands=false}) would quote it a second time, so it is refused.
 *
 * <p>A program ending in {@code .cmd} or {@code .bat} (an npm launcher such as {@code claude.cmd}, {@code gradlew.bat})
 * runs in {@code cmd.exe}, which reads the line by other rules: {@code %NAME%} is expanded even inside quotes, and
 * {@code & | < > ( ) ^ !} act outside them. Such a command is started as
 * {@code cmd.exe /d /v:off /s /c ""<script>" <args>"}: each argument is encoded as above, and every cmd.exe
 * metacharacter in it, the quotes included, is escaped with {@code ^} twice, once for this {@code cmd.exe} and once for
 * the script's own line that passes {@code %*} on to the real program (node, java), as cross-spawn does for npm. With
 * every quote escaped, cmd.exe never enters a quoted part, so every escape works.
 *
 * <p>A {@code %} needs more than its own caret: cmd.exe expands variables before it reads any caret, and besides
 * {@code %NAME%} it takes {@code %NAME:old=new%} and {@code %NAME:~start,length%}, where the name ends at the
 * {@code :} (#630). So the character after each {@code %} is escaped too, whatever it is: the text after any
 * {@code %} of an argument then starts with {@code ^}, so every name cmd.exe could look up there, in any of the three
 * forms, starts with {@code ^}. cmd.exe defines no such variable itself, and {@link #prepare} removes any from a
 * script's environment, so no argument expands one. The script's own line expands only {@code %*}, and not again in
 * the text it puts there. cmd.exe ends the command at a line break whatever the quoting, so an argument with one is
 * refused for a script rather than cut; the agent's prompt goes over stdin instead (ADR 0047).
 */
public final class WindowsCommandLine {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows");

    /** The JDK property whose {@code false} makes the JDK quote arguments itself. */
    static final String STRICT_PROPERTY = "jdk.lang.Process.allowAmbiguousCommands";

    /** What cmd.exe acts on outside quotes; each is escaped with {@code ^}. */
    private static final String CMD_METACHARACTERS = "\"%!^&|<>()";

    private WindowsCommandLine() {
    }

    /**
     * {@code builder} with its command made safe for Windows ({@link #forJdk}); on any other system it is left as it
     * is. For a {@code .cmd} or {@code .bat} program it also removes every variable whose name starts with {@code ^}
     * from the builder's environment ({@link #dropCaretVariables}). Throws when a command cannot be passed whole,
     * naming the argument.
     */
    public static ProcessBuilder prepare(ProcessBuilder builder) throws IOException {
        if (WINDOWS) {
            if (!builder.command().isEmpty() && isScript(builder.command().getFirst())) {
                dropCaretVariables(builder.environment());
            }
            try {
                builder.command(forJdk(builder.command(), System.getProperty(STRICT_PROPERTY, "true"),
                        System.getenv("ComSpec")));
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return builder;
    }

    /**
     * The list to give {@link ProcessBuilder} on Windows so that the program splits its command line back into
     * {@code command}: the program as it is, then each argument encoded ({@link #quote}), or for a {@code .cmd} or
     * {@code .bat} program the {@code cmd.exe} command that runs it.
     *
     * @param allowAmbiguous the value of {@value #STRICT_PROPERTY}, {@code "true"} when unset
     * @param comSpec        the {@code ComSpec} variable (cmd.exe's path), or null for {@code cmd.exe}
     */
    static List<String> forJdk(List<String> command, String allowAmbiguous, String comSpec) {
        if (command.isEmpty()) {
            return command;
        }
        if ("false".equalsIgnoreCase(allowAmbiguous)) {
            throw new IllegalArgumentException("-D" + STRICT_PROPERTY + "=false is set, so Java would quote each "
                    + "argument a second time; the bench quotes Windows arguments itself and needs Java's default");
        }
        String program = command.getFirst();
        List<String> out = new ArrayList<>(command.size());
        if (isScript(program)) {
            if (program.indexOf('"') >= 0) {
                throw new IllegalArgumentException("a script path cannot hold a quote: " + program);
            }
            StringBuilder line = new StringBuilder("\"\"").append(program).append('"');
            for (String argument : command.subList(1, command.size())) {
                if (argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("cmd.exe ends a command at a line break, so " + program
                            + " cannot get this argument whole: " + abbreviate(argument));
                }
                line.append(' ').append(escapeForCmd(escapeForCmd(quoteAlways(argument))));
            }
            out.add(comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec);
            out.addAll(List.of("/d", "/v:off", "/s", "/c"));
            out.add(line.append('"').toString());
            return List.copyOf(out);
        }
        out.add(program);
        for (String argument : command.subList(1, command.size())) {
            out.add(quote(argument));
        }
        return List.copyOf(out);
    }

    /** True for a program cmd.exe runs: a name ending in {@code .cmd} or {@code .bat}, in any case. */
    static boolean isScript(String program) {
        String name = program.toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    /**
     * {@code argument} as the C runtime reads it back: as it is when it has no blank and no quote and is not empty,
     * else {@link #quoteAlways}.
     */
    static String quote(String argument) {
        if (argument.isEmpty()) {
            return "\"\"";
        }
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '"' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return quoteAlways(argument);
            }
        }
        return argument;
    }

    /**
     * {@code argument} in quotes, by the C runtime's rules: backslashes before an inner quote are doubled and the quote
     * gets one more, backslashes before the closing quote are doubled, every other backslash stays single.
     */
    static String quoteAlways(String argument) {
        StringBuilder out = new StringBuilder(argument.length() + 2).append('"');
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                out.repeat('\\', backslashes * 2 + 1);
            } else {
                out.repeat('\\', backslashes);
            }
            backslashes = 0;
            out.append(c);
        }
        return out.repeat('\\', backslashes * 2).append('"').toString();
    }

    /**
     * {@code text} with each cmd.exe metacharacter, and each character right after a {@code %}, escaped by a
     * {@code ^}. Applied twice, {@code %x} becomes {@code ^^^%^^x} (and {@code %&} becomes {@code ^^^%^^^&}): cmd.exe
     * and then the script's {@code %*} line each take one caret level off, and a variable name read after the
     * {@code %} starts with {@code ^}.
     */
    static String escapeForCmd(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (CMD_METACHARACTERS.indexOf(c) >= 0 || i > 0 && text.charAt(i - 1) == '%') {
                out.append('^');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Removes from {@code environment} every variable whose name starts with {@code ^}, the names
     * {@link #escapeForCmd} leaves after a {@code %}, so none of them is defined for the cmd.exe that runs a script.
     */
    static void dropCaretVariables(Map<String, String> environment) {
        environment.keySet().removeIf(name -> name.startsWith("^"));
    }

    private static String abbreviate(String text) {
        String line = text.replace("\r", "\\r").replace("\n", "\\n");
        return line.length() <= 80 ? line : line.substring(0, 77) + "...";
    }
}
