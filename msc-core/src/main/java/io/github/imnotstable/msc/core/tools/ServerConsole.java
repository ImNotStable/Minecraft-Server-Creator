package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.LogBuffer;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Reading the server log ({@code server_logs}, {@code server_wait}) and typing into it ({@code server_command}). */
final class ServerConsole {
    static final int MAX_WAIT_SECONDS = 600;
    private static final Pattern LOG_PREFIX = Pattern.compile(
            "^(?:\\[[^\\]]*\\] (?:TRACE|DEBUG|INFO|WARN|ERROR) \\S+ - |\\[[^\\]]*\\]:? ?(\\[[^\\]]*(INFO|WARN|ERROR)\\]:? ?)?)");

    private final AgentHub hub;
    private final ServerManager server;
    private final LogMarks marks;

    ServerConsole(AgentHub hub, ServerManager server, LogMarks marks) {
        this.hub = hub;
        this.server = server;
        this.marks = marks;
    }

    String logs(Args args) {
        int maxChars = Budget.of(args);
        String mode = args.str("mode", "errors");
        if (!List.of("errors", "tail", "grep").contains(mode)) {
            throw new ToolException("mode must be errors, tail or grep");
        }
        List<String> lines = args.bool("all", false) ? server.log().all() : server.log().since(marks.start);
        if (lines.isEmpty()) {
            return "log is empty" + (server.running() ? "" : " (server not running)");
        }
        int count = Math.max(1, args.integer("lines", 30));
        return switch (mode) {
            case "errors" -> LogCondenser.condense(lines, maxChars);
            case "tail" -> Budget.truncate(LogText.tail(lines, count), maxChars, "Ask for fewer lines.");
            default -> grep(lines, pattern(args.str("pattern")), count, maxChars);
        };
    }

    private static String grep(List<String> lines, Pattern pattern, int count, int maxChars) {
        List<String> hits = new ArrayList<>();
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                hits.add(line);
            }
        }
        if (hits.isEmpty()) {
            return "no lines match in " + lines.size() + " lines";
        }
        String shown = LogText.tail(hits, count);
        return Budget.truncate(hits.size() > count ? hits.size() + " matches, last " + count + ":\n" + shown : shown,
                maxChars, "Use a narrower pattern.");
    }

    private static Pattern pattern(String regex) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new ToolException("bad regex: " + e.getDescription());
        }
    }

    String await(Args args) throws InterruptedException {
        Pattern pattern = pattern(args.str("until"));
        int timeout = args.integer("timeoutSeconds", 60, 1, MAX_WAIT_SECONDS);
        if (!server.running()) {
            throw new ToolException("server_wait reads the log of a server this session started, and none is running. "
                    + "Start it with server_start or dev_cycle.");
        }
        long from = Math.max(marks.waitFrom, marks.start);
        long started = System.nanoTime();
        LogBuffer.Match m = server.log().await(from, line -> pattern.matcher(line).find(),
                Duration.ofSeconds(timeout), server::running);
        String waited = LogText.seconds((System.nanoTime() - started) / 1_000_000);
        if (m != null) {
            marks.waitFrom = m.seq() + 1;
            return "matched after " + waited + ": " + m.line();
        }
        marks.waitFrom = server.log().mark();
        String last = LogText.tail(server.log().since(from), 5);
        String why = server.running() ? "no line matched in " + timeout + "s" : "the server stopped after " + waited;
        return why + (last.isEmpty() ? "; no new lines" : "; last lines:\n" + last);
    }

    /** Most commands one server_command call runs. */
    static final int MAX_COMMANDS = 64;

    /** One command, or several (one per line) run in order, each output under its command, in one tool call. */
    String command(Args args) throws IOException, InterruptedException {
        List<String> commands = args.text("command").lines().map(String::strip).filter(c -> !c.isEmpty()).toList();
        if (commands.size() > MAX_COMMANDS) {
            throw new ToolException("at most " + MAX_COMMANDS + " commands per call; got " + commands.size());
        }
        if (commands.size() <= 1) {
            return one(commands.isEmpty() ? "" : commands.getFirst());
        }
        StringBuilder out = new StringBuilder();
        for (String command : commands) {
            out.append(out.isEmpty() ? "" : "\n").append("> ").append(command).append('\n').append(one(command));
        }
        return Budget.truncate(out.toString(), Budget.DEFAULT_CHARS, "Output cut.");
    }

    private String one(String command) throws IOException, InterruptedException {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (hub.current() != null) {
            long before = server.log().mark();
            marks.waitFrom = before;
            Ops.CommandResult r = hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams(command));
            List<String> output = r.output() == null ? List.of() : r.output();
            List<String> console = server.running() ? consoleDuring(before, output) : List.of();
            StringBuilder text = new StringBuilder(r.success() ? "" : "command failed\n");
            text.append(output.isEmpty() && console.isEmpty() ? "(no output)" : String.join("\n", output));
            if (!console.isEmpty()) {
                // Broadcasts reach the console, not the command's sender: without these a /say-style reply is lost.
                text.append(output.isEmpty() ? "" : "\n").append("console during the command:\n")
                        .append(String.join("\n", console));
            }
            return Budget.truncate(text.toString(), Budget.DEFAULT_CHARS, "Output cut.");
        }
        long mark = server.log().mark();
        marks.waitFrom = mark;
        server.console(command);
        Thread.sleep(700);
        List<String> lines = server.log().since(mark);
        return lines.isEmpty() ? "sent (no output within 0.7s)"
                : Budget.truncate(LogText.tail(lines, 30), Budget.DEFAULT_CHARS, "Output cut.");
    }

    /**
     * What the server printed while a command ran, minus the lines the command already returned. The log is read on
     * another thread, so wait until it has been quiet for 60 ms (at most 400 ms).
     */
    private List<String> consoleDuring(long mark, List<String> returned) throws InterruptedException {
        long deadline = System.nanoTime() + 400_000_000L;
        long seen = -1;
        while (System.nanoTime() < deadline) {
            Thread.sleep(60);
            long now = server.log().mark();
            if (now == seen) {
                break;
            }
            seen = now;
        }
        return consoleLines(server.log().since(mark), returned);
    }

    /**
     * Console lines without log prefixes and without lines the command returned, at most 30. Stack traces are trimmed
     * like {@code server_logs} does, so an exception's header ("NullPointerException: Unloaded chunk") survives
     * instead of being pushed out by framework frames.
     */
    static List<String> consoleLines(List<String> lines, List<String> returned) {
        List<String> raw = new ArrayList<>();
        for (String line : lines) {
            raw.add(LOG_PREFIX.matcher(line).replaceFirst("").stripTrailing());
        }
        Set<String> skip = new HashSet<>(returned);
        List<String> out = new ArrayList<>();
        for (String line : LogCondenser.trimTrace(raw)) {
            String text = line.strip();
            if (!text.isEmpty() && !skip.contains(text)) {
                out.add(text);
            }
        }
        return out.size() > 30 ? out.subList(out.size() - 30, out.size()) : out;
    }
}
