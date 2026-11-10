package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.templates.ScaffoldResult;
import io.github.imnotstable.msc.templates.TemplateVariables;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Project and server lifecycle: scaffold, set up, start, stop, build-deploy-restart, logs, commands. */
public final class LifecycleTools {
    private final CoreConfig config;
    private final AgentHub hub;
    private final ServerManager server;
    private final ProjectBuilder builder;
    private final PaperDownloader downloader;
    private volatile long startMark;

    private LifecycleTools(CoreConfig config, AgentHub hub, ServerManager server, ProjectBuilder builder,
                           PaperDownloader downloader) {
        this.config = config;
        this.hub = hub;
        this.server = server;
        this.builder = builder;
        this.downloader = downloader;
    }

    public static void register(ToolRegistry registry, CoreConfig config, AgentHub hub, ServerManager server,
                                ProjectBuilder builder, PaperDownloader downloader) {
        LifecycleTools t = new LifecycleTools(config, hub, server, builder, downloader);
        registry.add("project_scaffold",
                "Create a new plugin project from a template (Gradle KTS, tests wired in). Target directory must be empty.",
                Schema.of().req("name", "string", "Plugin name, for example MyPlugin")
                        .opt("dir", "string", "Target directory, relative to the workspace. Default: the workspace itself")
                        .opt("template", "string", "Template id. Default paper-plugin"),
                t::scaffold);
        registry.add("server_setup",
                "Prepare the dev server: download the Paper jar, write dev defaults, install the msc agent plugin.",
                Schema.of().opt("version", "string", "Minecraft version. Default " + config.minecraft()),
                t::setup);
        registry.add("server_start", "Start the dev server and wait until it is ready. Returns startup errors only.",
                Schema.of().opt("timeoutSeconds", "integer", "Default 180"), t::start);
        registry.add("server_stop", "Stop the dev server.", Schema.of(), args ->
                server.stop(Duration.ofSeconds(30)) ? "server stopped" : "server was not running");
        registry.add("dev_cycle",
                "Build the plugin, deploy the jar, restart the server and report: one call for the whole edit-test loop. "
                        + "Returns compile or test failures, or runtime errors from startup.",
                Schema.of().opt("project", "string", "Project directory, relative to the workspace. Default: the workspace")
                        .opt("restart", "boolean", "Restart the server after deploying. Default true")
                        .opt("maxChars", "integer", "Result size cap. Default 6000"),
                t::devCycle);
        registry.add("server_logs",
                "Read the server log cheaply. mode=errors: warnings and errors with trimmed stack traces, repeats collapsed. "
                        + "mode=tail: last lines. mode=grep: lines matching a regex.",
                Schema.of().opt("mode", "string", "errors (default), tail or grep")
                        .opt("pattern", "string", "Regex for mode=grep")
                        .opt("lines", "integer", "Line count for tail and grep. Default 30")
                        .opt("all", "boolean", "Look at the whole log, not only since the last start. Default false")
                        .opt("maxChars", "integer", "Result size cap. Default 6000"),
                t::logs);
        registry.add("server_command", "Run a console command on the server and return its output.",
                Schema.of().req("command", "string", "Command without the leading slash"), t::command);
    }

    private String scaffold(Args args) throws IOException {
        String name = args.str("name");
        Path dir = config.resolve(args.str("dir", "."));
        String template = args.str("template", Templates.PAPER_PLUGIN);
        if (!Templates.ids().contains(template)) {
            throw new ToolException("unknown template " + template + ". Available: " + String.join(", ", Templates.ids()));
        }
        ScaffoldResult result;
        try {
            result = Templates.scaffold(template, dir, TemplateVariables.of(name));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
        StringBuilder out = new StringBuilder();
        out.append("created ").append(template).append(" project in ").append(result.root()).append(" (")
                .append(result.files().size()).append(" files)");
        result.values().forEach((k, v) -> {
            if (!k.toLowerCase().contains("password")) {
                out.append('\n').append(k).append(": ").append(v);
            }
        });
        out.append("\nnext: edit the code, then dev_cycle");
        return out.toString();
    }

    private String setup(Args args) throws IOException, InterruptedException {
        if (server.running()) {
            throw new ToolException("stop the server before server_setup");
        }
        String version = args.str("version", config.minecraft());
        Path dir = config.serverDir();
        StringBuilder out = new StringBuilder();
        boolean empty = !Files.exists(dir);
        if (!empty) {
            try (Stream<Path> list = Files.list(dir)) {
                empty = list.findAny().isEmpty();
            }
        }
        if (empty && Templates.ids().contains(Templates.PAPER_SERVER)) {
            ScaffoldResult defaults = Templates.scaffold(Templates.PAPER_SERVER, dir, TemplateVariables.of("server"));
            out.append("wrote ").append(defaults.files().size()).append(" default config files\n");
        }
        Files.createDirectories(dir.resolve("plugins"));
        PaperDownloader.Download d = downloader.download(version);
        Files.copy(d.jar(), dir.resolve("paper.jar"), StandardCopyOption.REPLACE_EXISTING);
        out.append("paper ").append(d.version()).append(" build ").append(d.build())
                .append(d.cached() ? " (cached)" : " (downloaded)").append('\n');
        Path agent = findAgentJar();
        if (agent != null) {
            Files.copy(agent, dir.resolve("plugins").resolve("msc-agent-paper.jar"), StandardCopyOption.REPLACE_EXISTING);
            out.append("agent plugin installed\n");
        } else {
            out.append("warning: msc agent plugin not found, so world and build tools will not work. Put its jar in ")
                    .append(config.dataDir().resolve("agent")).append(" or set MSC_AGENT_JAR, then run server_setup again\n");
        }
        out.append("server ready in ").append(dir).append("; next: server_start or dev_cycle");
        return out.toString();
    }

    private Path findAgentJar() throws IOException {
        if (config.agentJar() != null) {
            return Files.exists(config.agentJar()) ? config.agentJar() : null;
        }
        Path dir = config.dataDir().resolve("agent");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst().orElse(null);
        }
    }

    private String start(Args args) throws IOException, InterruptedException {
        return startAndReport(args.integer("timeoutSeconds", 180), Budget.DEFAULT_CHARS);
    }

    private String startAndReport(int timeoutSeconds, int maxChars) throws IOException, InterruptedException {
        ServerManager.StartResult r = server.start(Duration.ofSeconds(timeoutSeconds));
        startMark = r.logMark();
        List<String> lines = server.log().since(r.logMark());
        LogCondenser.Condensed analysis = LogCondenser.analyze(lines, maxChars);
        String condensed = analysis.text();
        boolean clean = analysis.clean();
        StringBuilder out = new StringBuilder();
        if (r.ready()) {
            out.append("server ready in ").append(seconds(r.millis()));
            CoreServer.AgentSession s = hub.current();
            out.append(s != null ? ", agent connected" : ", no agent connected (world tools unavailable)");
            out.append('\n').append(clean ? "no errors or warnings" : condensed);
        } else if (r.exited()) {
            out.append("server exited during startup after ").append(seconds(r.millis())).append('\n').append(condensed);
            if (clean) {
                out.append("\nlast lines:\n").append(tail(lines, 15));
            }
        } else {
            out.append("server not ready after ").append(timeoutSeconds).append("s (still starting)\n").append(condensed);
        }
        return out.toString();
    }

    private String devCycle(Args args) throws IOException, InterruptedException {
        int maxChars = Budget.of(args);
        Path project = config.resolve(args.str("project", "."));
        ProjectBuilder.BuildResult build = builder.build(project, Duration.ofMinutes(10));
        if (!build.ok()) {
            return "BUILD FAILED in " + seconds(build.millis()) + "\n"
                    + LogCondenser.condenseBuild(build.output(), maxChars);
        }
        if (build.jar() == null) {
            return "build ok in " + seconds(build.millis()) + " but no jar in " + project.resolve("build/libs")
                    + "; nothing deployed";
        }
        StringBuilder out = new StringBuilder("build ok in " + seconds(build.millis()));
        boolean restart = args.bool("restart", true);
        boolean wasRunning = server.running();
        if (wasRunning && restart) {
            server.stop(Duration.ofSeconds(30));
        }
        Path plugins = config.serverDir().resolve("plugins");
        Files.createDirectories(plugins);
        String name = project.getFileName() == null ? "plugin" : project.getFileName().toString();
        Path target = plugins.resolve(name + ".jar");
        try {
            Files.copy(build.jar(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolException("cannot replace " + target + " (" + e.getMessage()
                    + "). The server holds the jar open; call dev_cycle with restart=true.");
        }
        out.append(", deployed ").append(build.jar().getFileName()).append(" as plugins/").append(target.getFileName());
        if (!restart) {
            return out.append(wasRunning ? "; server still running the old jar until restarted" : "").toString();
        }
        if (server.serverJar() == null && config.serverCommand() == null) {
            return out.append("\nserver not set up, so nothing was started. Run server_setup, then dev_cycle.").toString();
        }
        return out.append('\n').append(startAndReport(180, maxChars)).toString();
    }

    private String logs(Args args) {
        int maxChars = Budget.of(args);
        String mode = args.str("mode", "errors");
        if (!List.of("errors", "tail", "grep").contains(mode)) {
            throw new ToolException("mode must be errors, tail or grep");
        }
        List<String> lines = args.bool("all", false) ? server.log().all() : server.log().since(startMark);
        if (lines.isEmpty()) {
            return "log is empty" + (server.running() ? "" : " (server not running)");
        }
        int count = Math.max(1, args.integer("lines", 30));
        switch (mode) {
            case "errors":
                return LogCondenser.condense(lines, maxChars);
            case "tail":
                return Budget.truncate(tail(lines, count), maxChars, "Ask for fewer lines.");
            case "grep": {
                Pattern pattern;
                try {
                    pattern = Pattern.compile(args.str("pattern"), Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    throw new ToolException("bad regex: " + e.getDescription());
                }
                List<String> hits = new ArrayList<>();
                for (String line : lines) {
                    if (pattern.matcher(line).find()) {
                        hits.add(line);
                    }
                }
                if (hits.isEmpty()) {
                    return "no lines match in " + lines.size() + " lines";
                }
                String shown = tail(hits, count);
                return Budget.truncate(hits.size() > count ? hits.size() + " matches, last " + count + ":\n" + shown : shown,
                        maxChars, "Use a narrower pattern.");
            }
            default:
                throw new ToolException("mode must be errors, tail or grep");
        }
    }

    private String command(Args args) throws IOException, InterruptedException {
        String command = args.str("command").strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (hub.current() != null) {
            Ops.CommandResult r = hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams(command));
            String output = r.output() == null || r.output().isEmpty() ? "(no output)" : String.join("\n", r.output());
            return Budget.truncate((r.success() ? "" : "command failed\n") + output, Budget.DEFAULT_CHARS,
                    "Output cut.");
        }
        long mark = server.log().mark();
        server.console(command);
        Thread.sleep(700);
        List<String> lines = server.log().since(mark);
        return lines.isEmpty() ? "sent (no output within 0.7s)" : Budget.truncate(tail(lines, 30), Budget.DEFAULT_CHARS, "Output cut.");
    }

    private static String tail(List<String> lines, int count) {
        StringBuilder out = new StringBuilder();
        for (String line : lines.subList(Math.max(0, lines.size() - count), lines.size())) {
            out.append(line.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "")).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String seconds(long millis) {
        return String.format("%.1fs", millis / 1000.0);
    }
}
