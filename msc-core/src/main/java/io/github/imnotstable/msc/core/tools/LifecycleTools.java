package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolRegistry;

/**
 * Project and server lifecycle tools: scaffold ({@link Scaffold}), set up ({@link ServerSetup}), start, stop and
 * detach ({@link ServerRun}), build-deploy-restart ({@link DevCycle}), and logs, waits and commands
 * ({@link ServerConsole}). This class only declares them.
 */
public final class LifecycleTools {
    /** The description the pack tool uses when none is given. */
    public static final String DEFAULT_PACK_DESCRIPTION = "Minecraft Server Creator dev pack";

    private LifecycleTools() {
    }

    public static void register(ToolRegistry registry, CoreConfig config, AgentHub hub, ServerManager server,
                                ResourcePack pack, ProjectBuilder builder, PaperDownloader downloader,
                                FabricDownloader fabric, BackgroundWork background) {
        EulaPrompts eula = new EulaPrompts(config);
        LogMarks marks = new LogMarks();
        BuildWarmup warmup = new BuildWarmup(builder, background);
        Scaffold scaffold = new Scaffold(config, eula, warmup);
        ServerSetup setup = new ServerSetup(config, server, downloader, fabric, eula);
        ServerRun run = new ServerRun(config, hub, server, pack, setup, eula, marks);
        DevCycle cycle = new DevCycle(config, server, builder, run, warmup, background);
        ServerConsole console = new ServerConsole(hub, server, marks);
        registry.add("project_scaffold",
                "Create a new project from a template (Gradle KTS, tests wired in). Target directory must be empty.",
                Schema.of().req("name", "string", "Project name, for example MyPlugin")
                        .opt("dir", "string", "Target directory in the workspace. Default: the workspace itself")
                        .opt("template", "string", "paper-plugin (default), fabric-mod (also Quilt), neoforge-mod, forge-mod, minestom-server, or a minigame base (kit, stages, saved progress, /game debug "
                                + "commands): paper-game, fabric-game, minestom-game")
                        .opt("package", "string", "Java package. Default com.example.<name>")
                        .opt("acceptEula", "boolean", EulaPrompts.SCAFFOLD_EULA_ARG),
                scaffold::run);
        registry.add("server_setup",
                "Prepare the dev server: download or install it, write dev defaults, install the msc agent.",
                Schema.of().opt("version", "string", "Minecraft version. Default " + config.minecraft())
                        .opt("platform", "string", "paper, purpur, fabric, quilt, neoforge, forge or minestom. Default: the project's")
                        .opt("mode", "string", "build (creative, peaceful) or game (survival, normal)")
                        .opt("acceptEula", "boolean", EulaPrompts.EULA_ARG),
                setup::run);
        registry.add("server_start", "Start the dev server and wait until it is ready. Returns startup errors only.",
                Schema.of().opt("timeoutSeconds", "integer", "Default 180, 1 to " + ServerRun.MAX_START_SECONDS).opt("acceptEula", "boolean", EulaPrompts.EULA_ARG),
                run::start);
        registry.add("server_stop", "Stop the dev server.", Schema.of(), run::stop);
        registry.add("server_detach",
                "Keep the dev server and the pack host running after this session ends, so the user can play. "
                        + "Once this session ends, the next session's server_start, dev_cycle or server_stop stops them.",
                Schema.of(), run::detach);
        registry.add("dev_cycle",
                "Build, deploy and restart in one call: the edit-test loop. Tests run during the restart; an unchanged "
                        + "jar skips it. Returns build, test and startup errors, and phase times.",
                Schema.of().opt("project", "string", "Project directory in the workspace. Default: the workspace")
                        .opt("restart", "boolean", "Restart the server after deploying. Default true")
                        .opt("maxChars", "integer", "Result size cap. Default 6000"),
                cycle::run);
        registry.add("server_logs",
                "Read the server log cheaply. mode=errors: warnings and errors with trimmed stack traces, repeats collapsed. "
                        + "mode=tail: last lines. mode=grep: lines matching a regex.",
                Schema.of().opt("mode", "string", "errors (default), tail or grep")
                        .opt("pattern", "string", "Regex for mode=grep")
                        .opt("lines", "integer", "Line count for tail and grep. Default 30")
                        .opt("all", "boolean", "Look at the whole log, not only since the last start. Default false")
                        .opt("maxChars", "integer", "Result size cap. Default 6000"),
                console::logs);
        registry.add("server_command", "Run console commands on the server and return their output.",
                Schema.of().req("command", "string", "Command without the leading slash; several: one per line"),
                console::command);
        registry.add("server_wait", "Wait until a log or chat line matches a regex, instead of polling.",
                Schema.of().req("until", "string", "Regex, case-insensitive")
                        .opt("timeoutSeconds", "integer", "Default 60, 1 to " + ServerConsole.MAX_WAIT_SECONDS), console::await);
    }
}
