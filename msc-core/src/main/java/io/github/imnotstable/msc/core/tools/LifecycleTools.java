package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.Platform;
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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Project and server lifecycle: scaffold, set up, start, stop, build-deploy-restart, logs, commands. */
public final class LifecycleTools {
    private static final String EULA_ARG =
            "Set true only if the user has agreed to the Minecraft EULA (https://aka.ms/MinecraftEULA)";
    private final CoreConfig config;
    private final AgentHub hub;
    private final ServerManager server;
    private final ProjectBuilder builder;
    private final PaperDownloader downloader;
    private final FabricDownloader fabric;
    private volatile long startMark;

    private LifecycleTools(CoreConfig config, AgentHub hub, ServerManager server, ProjectBuilder builder,
                           PaperDownloader downloader, FabricDownloader fabric) {
        this.fabric = fabric;
        this.config = config;
        this.hub = hub;
        this.server = server;
        this.builder = builder;
        this.downloader = downloader;
    }

    public static void register(ToolRegistry registry, CoreConfig config, AgentHub hub, ServerManager server,
                                ProjectBuilder builder, PaperDownloader downloader, FabricDownloader fabric) {
        LifecycleTools t = new LifecycleTools(config, hub, server, builder, downloader, fabric);
        registry.add("project_scaffold",
                "Create a new project from a template (Gradle KTS, tests wired in). Target directory must be empty.",
                Schema.of().req("name", "string", "Project name, for example MyPlugin")
                        .opt("dir", "string", "Target directory, relative to the workspace. Default: the workspace itself")
                        .opt("template", "string", "paper-plugin (default), fabric-mod or minestom-server")
                        .opt("acceptEula", "boolean", EULA_ARG),
                t::scaffold);
        registry.add("server_setup",
                "Prepare the dev server: download Paper or the Fabric launcher, write dev defaults, install the msc agent.",
                Schema.of().opt("version", "string", "Minecraft version. Default " + config.minecraft())
                        .opt("platform", "string", "paper, fabric or minestom. Default: the project's")
                        .opt("acceptEula", "boolean", EULA_ARG),
                t::setup);
        registry.add("server_start", "Start the dev server and wait until it is ready. Returns startup errors only.",
                Schema.of().opt("timeoutSeconds", "integer", "Default 180"), t::start);
        registry.add("server_stop", "Stop the dev server.", Schema.of(), args ->
                server.stop(Duration.ofSeconds(30)) ? "server stopped" : "server was not running");
        registry.add("dev_cycle",
                "Build the project, deploy its jar, restart the server and report: one call for the whole edit-test loop. "
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
            TemplateVariables vars = TemplateVariables.of(name).withAcceptEula(args.bool("acceptEula", false));
            result = onlyCoreData(dir) ? scaffoldBesideCoreData(template, dir, vars)
                    : Templates.scaffold(template, dir, vars);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
        String platformId = Templates.info(template).get("platform");
        if (platformId != null && !template.equals(Templates.PAPER_SERVER)
                && (dir.equals(config.workspace()) || !Files.exists(Platform.file(config)))) {
            Platform.remember(config, Platform.of(platformId));
        }
        StringBuilder out = new StringBuilder();
        out.append("created ").append(template).append(" project in ").append(dir).append(" (")
                .append(result.files().size()).append(" files)");
        // Only what the AI needs to find its way around; the template's pinned versions are in its build files.
        for (String key : List.of("projectId", "packageName", "version")) {
            if (result.values().containsKey(key)) {
                out.append('\n').append(key).append(": ").append(result.values().get(key));
            }
        }
        out.append("\nnext: edit the code, then dev_cycle");
        return out.toString();
    }

    /** True when {@code dir} holds nothing but the core's own {@code .msc} folder, as the workspace does after any call. */
    private boolean onlyCoreData(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> entries = list.toList();
            return entries.size() == 1 && entries.getFirst().equals(config.dataDir());
        }
    }

    /** Templates only write into empty directories, so render into a staging folder and move the files in. */
    private ScaffoldResult scaffoldBesideCoreData(String template, Path dir, TemplateVariables vars) throws IOException {
        Path staging = Files.createTempDirectory(config.dataDir(), "scaffold-");
        Path staged = staging.resolve("project");
        ScaffoldResult result = Templates.scaffold(template, staged, vars);
        try (Stream<Path> list = Files.list(staged)) {
            for (Path entry : list.toList()) {
                Files.move(entry, dir.resolve(entry.getFileName().toString()));
            }
        }
        Files.delete(staged);
        Files.delete(staging);
        return result;
    }

    private String setup(Args args) throws IOException, InterruptedException {
        if (server.running()) {
            throw new ToolException("stop the server before server_setup");
        }
        String version = args.str("version", config.minecraft());
        boolean acceptEula = args.bool("acceptEula", false);
        Platform platform = server.platform();
        if (args.str("platform", null) != null) {
            platform = Platform.of(args.str("platform"));
            Platform.remember(config, platform);
        }
        Path dir = config.serverDir();
        if (platform == Platform.MINESTOM) {
            Files.createDirectories(dir);
            return "minestom needs no server download and has no EULA step: the project's own jar is the server and "
                    + "the msc agent is built into it. Server directory: " + dir + "; next: dev_cycle";
        }
        StringBuilder out = new StringBuilder();
        boolean empty = !Files.exists(dir);
        if (!empty) {
            try (Stream<Path> list = Files.list(dir)) {
                empty = list.findAny().isEmpty();
            }
        }
        if (empty && Templates.ids().contains(Templates.PAPER_SERVER)) {
            // paper-server holds plain vanilla server config (server.properties, eula.txt), so Fabric uses it too.
            ScaffoldResult defaults = Templates.scaffold(Templates.PAPER_SERVER, dir,
                    TemplateVariables.of("server").withAcceptEula(acceptEula));
            out.append("wrote ").append(defaults.files().size()).append(" default config files\n");
        }
        Path addons = Files.createDirectories(dir.resolve(platform.addonDir()));
        if (platform == Platform.FABRIC) {
            setupFabric(version, dir, addons, out);
        } else {
            PaperDownloader.Download d = downloader.download(version);
            Files.copy(d.jar(), dir.resolve(platform.serverJar()), StandardCopyOption.REPLACE_EXISTING);
            out.append("paper ").append(d.version()).append(" build ").append(d.build())
                    .append(d.cached() ? " (cached)" : " (downloaded)").append('\n');
        }
        Path agent = findAgentJar(platform);
        if (agent != null) {
            Files.copy(agent, addons.resolve("msc-agent-" + platform.id() + ".jar"), StandardCopyOption.REPLACE_EXISTING);
            out.append("agent installed in ").append(platform.addonDir()).append("/\n");
        } else {
            out.append("warning: msc ").append(platform.id()).append(" agent not found, so world and build tools will "
                    + "not work. Put its jar in ").append(config.dataDir().resolve("agent"))
                    .append(" or set MSC_AGENT_JAR, then run server_setup again\n");
        }
        Path eula = dir.resolve("eula.txt");
        if (acceptEula && Files.exists(eula) && !ServerManager.eulaAccepted(eula)) {
            Files.writeString(eula, Files.readString(eula).replaceAll("(?m)^eula=false[ \t]*$", "eula=true"));
        }
        if (!ServerManager.eulaAccepted(eula)) {
            out.append("the Minecraft EULA is not accepted: once the user agrees to https://aka.ms/MinecraftEULA, run "
                    + "server_setup with acceptEula=true (or set eula=true in ").append(eula)
                    .append("); server_start refuses until then\n");
        } else {
            out.append("note: eula.txt has eula=true; running the server means the user agrees to https://aka.ms/MinecraftEULA\n");
        }
        out.append(platform.id()).append(" server ready in ").append(dir).append("; next: server_start or dev_cycle");
        return out.toString();
    }

    /** Fabric: the server launcher plus the Fabric API mod, at the versions the fabric-mod template pins. */
    private void setupFabric(String version, Path dir, Path mods, StringBuilder out) throws IOException, InterruptedException {
        Map<String, String> pinned = Templates.versions();
        String loader = pinned.get("fabricLoaderVersion");
        String api = pinned.get("fabricApiVersion");
        if (loader == null || api == null) {
            throw new ToolException("this build of msc-core has no Fabric versions pinned");
        }
        if (!api.endsWith("+" + version)) {
            throw new ToolException("Fabric setup supports Minecraft " + api.substring(api.indexOf('+') + 1)
                    + " (the pinned Fabric API is " + api + "), not " + version);
        }
        FabricDownloader.Download launcher = fabric.launcher(version, loader);
        Files.copy(launcher.jar(), dir.resolve(Platform.FABRIC.serverJar()), StandardCopyOption.REPLACE_EXISTING);
        out.append("fabric launcher for ").append(version).append(", loader ").append(loader)
                .append(launcher.cached() ? " (cached)" : " (downloaded)")
                .append("; it downloads the Minecraft server on first start\n");
        FabricDownloader.Download apiJar = fabric.api(api);
        Files.copy(apiJar.jar(), mods.resolve("fabric-api.jar"), StandardCopyOption.REPLACE_EXISTING);
        out.append("fabric api ").append(api).append(apiJar.cached() ? " (cached)" : " (downloaded)").append('\n');
    }

    /** The agent jar for a platform: the configured one, else the best match in {@code .msc/agent}. */
    private Path findAgentJar(Platform platform) throws IOException {
        if (config.agentJar() != null) {
            return Files.exists(config.agentJar()) ? config.agentJar() : null;
        }
        Path dir = config.dataDir().resolve("agent");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> jars = list.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
            for (Path jar : jars) {
                if (jar.getFileName().toString().contains(platform.id())) {
                    return jar;
                }
            }
            // A jar named for another platform would not load; an unmarked one is taken on trust.
            return jars.stream().filter(jar -> java.util.Arrays.stream(Platform.values())
                    .noneMatch(other -> jar.getFileName().toString().contains(other.id()))).findFirst().orElse(null);
        }
    }

    private String start(Args args) throws IOException, InterruptedException {
        return startAndReport(args.integer("timeoutSeconds", 180), Budget.DEFAULT_CHARS);
    }

    private String startAndReport(int timeoutSeconds, int maxChars) throws IOException, InterruptedException {
        boolean agentUpdated = refreshAgent();
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
            if (agentUpdated) {
                out.append(", agent jar updated");
            }
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

    /**
     * Before a start, replace an installed agent jar that differs from the one on offer, so a rebuilt agent
     * is picked up without another server_setup. Does nothing for a server that was never set up.
     */
    private boolean refreshAgent() throws IOException {
        Platform platform = server.platform();
        if (platform.addonDir() == null || server.running()) {
            return false;
        }
        Path installed = config.serverDir().resolve(platform.addonDir()).resolve("msc-agent-" + platform.id() + ".jar");
        Path agent = findAgentJar(platform);
        if (agent == null || !Files.exists(installed) || Files.mismatch(agent, installed) == -1) {
            return false;
        }
        Files.copy(agent, installed, StandardCopyOption.REPLACE_EXISTING);
        return true;
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
        Platform platform = Platform.detect(project);
        if (platform == null) {
            platform = server.platform();
        } else if (!Files.exists(Platform.file(config))) {
            Platform.remember(config, platform);
        }
        Path target;
        String shown;
        if (platform.addonDir() == null) {
            // Minestom: the project jar is the server itself.
            Files.createDirectories(config.serverDir());
            target = config.serverDir().resolve(platform.serverJar());
            shown = platform.serverJar();
        } else {
            Path addons = Files.createDirectories(config.serverDir().resolve(platform.addonDir()));
            String name = project.getFileName() == null ? "project" : project.getFileName().toString();
            target = addons.resolve(name + ".jar");
            shown = platform.addonDir() + "/" + target.getFileName();
        }
        try {
            Files.copy(build.jar(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolException("cannot replace " + target + " (" + e.getMessage()
                    + "). The server holds the jar open; call dev_cycle with restart=true.");
        }
        out.append(", deployed ").append(build.jar().getFileName()).append(" as ").append(shown);
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
