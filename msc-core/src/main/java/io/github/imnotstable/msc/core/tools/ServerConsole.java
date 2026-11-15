package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.CrashTriage;
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

    String logs(Args args) throws InterruptedException {
        String mode = args.str("mode", "errors");
        if (!WayArgs.LOG_MODES.contains(mode)) {
            throw new ToolException("mode must be errors, tail, grep or triage");
        }
        // Checked first (#619): an argument this mode does not read is refused, not dropped with an OK.
        WayArgs.check(args, Set.of(mode), WayArgs.LOGS);
        // A server that just exited: its last lines may still be in the pipe (#533).
        server.awaitLogEnd();
        int maxChars = Budget.of(args);
        boolean all = args.bool("all", false);
        List<String> lines = all ? server.log().all() : server.log().since(marks.start);
        if (mode.equals("triage")) {
            // Crash files from before the last start are old news, unless all=true or this core never started one.
            CrashTriage.Result triage = CrashTriage.triage(lines, server.serverDir(),
                    all ? null : server.lastLaunch(), server.platform());
            String state = server.running() ? "server running" : "server not running";
            return Budget.truncate(triage.render(state), maxChars,
                    "Use server_logs mode=errors.");
        }
        if (lines.isEmpty()) {
            return "log is empty" + (server.running() ? "" : " (server not running)");
        }
        int count = args.integer("lines", 30);
        return switch (mode) {
            case "errors" -> LogCondenser.condense(lines, maxChars);
            case "tail" -> Budget.tail(LogText.tail(lines, count), maxChars,
                    Budget.raise(maxChars, "Use mode=grep to narrow."));
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
        String head = hits.size() > count ? hits.size() + " matches, last " + count + ":\n" : "";
        return head + Budget.tail(LogText.tail(hits, count), maxChars - head.length(), "Use a narrower pattern.");
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
        int timeout = args.integer("timeoutSeconds", 60);
        if (!server.running()) {
            // A server another session runs or left running is named, as msc_status names it (#705).
            String why = server.notRunning();
            throw new ToolException("server_wait reads the log of a server this session started, and none is running"
                    + (why.equals(AgentHub.NOT_RUNNING) ? ". Start it with server_start or dev_cycle." : "; " + why));
        }
        long from = Math.max(marks.waitFrom, marks.start);
        long started = System.nanoTime();
        LogBuffer.Match m = server.awaitLine(from, line -> pattern.matcher(line).find(), Duration.ofSeconds(timeout));
        String waited = LogText.seconds((System.nanoTime() - started) / 1_000_000);
        if (m != null) {
            marks.waitFrom = m.seq() + 1;
            return "matched after " + waited + ": " + m.line();
        }
        marks.waitFrom = server.log().mark();
        String last = LogText.tail(server.log().since(from), 5);
        String why = server.running() ? "no line matched in " + timeout + "s" : "the server stopped after " + waited;
        if (last.isEmpty()) {
            return why + "; no new lines";
        }
        why += "; last lines:\n";
        return why + Budget.tail(last, Budget.DEFAULT_CHARS - why.length(), "Use server_logs to see more.");
    }

    /** Most commands one server_command call runs. */
    static final int MAX_COMMANDS = 64;
    private static final String CUT = "Output cut.";

    /** One command, or several (one per line) run in order, each output under its command, in one tool call. */
    String command(Args args) throws IOException, InterruptedException {
        List<String> commands = args.text("command").lines().map(String::strip).filter(c -> !c.isEmpty()).toList();
        if (commands.size() > MAX_COMMANDS) {
            throw new ToolException("at most " + MAX_COMMANDS + " commands per call; got " + commands.size());
        }
        if (commands.size() <= 1) {
            return one(commands.isEmpty() ? "" : commands.getFirst()).fit(Budget.DEFAULT_CHARS);
        }
        // One budget (#473): every command keeps its line, the outputs share what is left.
        Budget.Parts out = new Budget.Parts();
        for (String command : commands) {
            Output output = one(command);
            out.add((out.isEmpty() ? "" : "\n") + "> " + command + "\n").cut(output::fit);
        }
        return out.text(Budget.DEFAULT_CHARS, null, "Run fewer commands per call.");
    }

    /**
     * What one command printed: its own reply, then under {@code label} the end of the log it caused (#611). A cut
     * keeps the start of the reply and the end of the log, so the newest log lines survive.
     */
    record Output(String reply, String label, String log) {
        String whole() {
            return reply + (reply.isEmpty() || log.isEmpty() ? "" : "\n") + label + log;
        }

        /** The output in at most {@code n} characters. */
        String fit(int n) {
            String whole = whole();
            if (whole.length() <= n || log.isEmpty()) {
                return Budget.truncate(whole, n, CUT);
            }
            int fixed = (reply.isEmpty() ? 0 : 1) + label.length();
            if (n < fixed) {
                return Budget.tail(whole, n, CUT);
            }
            int[] shares = Budget.split(n - fixed, reply.length(), log.length());
            return (reply.isEmpty() ? "" : Budget.truncate(reply, shares[0], CUT) + "\n") + label
                    + Budget.tail(log, shares[1], CUT);
        }
    }

    private Output one(String command) throws IOException, InterruptedException {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        // The agent of a stopping server is not asked, its console is: it refuses this session's own stop as the agent
        // would (#723), and still takes a command after a stop only the agent announced (#729).
        if (hub.current() != null && !server.stopping()) {
            long before = server.log().mark();
            marks.waitFrom = before;
            Ops.CommandResult r = hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams(command));
            List<String> output = r.output() == null ? List.of() : r.output();
            List<String> console = server.running() ? consoleDuring(before, output) : List.of();
            List<String> reply = new ArrayList<>(r.success() ? List.of() : List.of("command failed"));
            reply.addAll(output.isEmpty() && console.isEmpty() ? List.of("(no output)") : output);
            // Broadcasts reach the console, not the command's sender: without these a /say-style reply is lost.
            return new Output(String.join("\n", reply), console.isEmpty() ? "" : "console during the command:\n",
                    String.join("\n", console));
        }
        long mark = server.log().mark();
        marks.waitFrom = mark;
        server.command(command);
        Thread.sleep(700);
        List<String> lines = server.log().since(mark);
        return lines.isEmpty() ? new Output("sent (no output within 0.7s)", "", "")
                : new Output("", "", LogText.tail(lines, 30));
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
