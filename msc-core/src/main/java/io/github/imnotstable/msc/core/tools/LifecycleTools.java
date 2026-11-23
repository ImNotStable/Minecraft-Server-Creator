package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.AddonSources;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.WorldService;

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

    /** Declares the tools; returns the world backups, which share the server start ({@code build_undo backup=}). */
    public static WorldBackups register(ToolRegistry registry, CoreConfig config, AgentHub hub, ServerManager server,
                                        ResourcePack pack, PackPublisher publisher, ProjectBuilder builder,
                                        PaperDownloader downloader, FabricDownloader fabric, BackgroundWork background,
                                        WorldService world) {
        EulaPrompts eula = new EulaPrompts(config);
        LogMarks marks = new LogMarks();
        BuildWarmup warmup = new BuildWarmup(builder, background);
        Scaffold scaffold = new Scaffold(config, eula, warmup);
        ServerSetup setup = new ServerSetup(config, server, downloader, fabric, eula);
        ServerRun run = new ServerRun(config, hub, server, pack, publisher, setup, eula, marks);
        // An agent that becomes ready after its start was reported (a slow start, a restart outside MSC) gets the
        // workspace's pack too, beside the call that runs then (#655).
        hub.onReady(() -> {
            try {
                background.start("msc-pack-send", run::packForAgent);
            } catch (ToolException closed) {
                // The workspace is closing: no server of it is left to send to.
            }
        });
        DevCycle cycle = new DevCycle(config, server, builder, run, warmup, background);
        ServerConsole console = new ServerConsole(hub, server, marks);
        WorldBackups backups = new WorldBackups(config, hub, server, run, world, java.time.Clock.systemDefaultZone());
        registry.add("project_scaffold",
                "Create a bare project from a template (Gradle KTS build, dev server task, empty entry point). Target directory must be empty.",
                Schema.of().req("name", "string", "Project name, for example MyPlugin")
                        .opt("dir", "string", "Folder in the workspace. Default: the workspace")
                        .opt("template", "string", "paper-plugin (default), fabric-mod (also Quilt), neoforge-mod, forge-mod, minestom-server, velocity-plugin or waterfall-plugin")
                        .opt("package", "string", "Java package. Default com.example.<name>")
                        .opt("acceptEula", "boolean", EulaPrompts.SCAFFOLD_EULA_ARG),
                scaffold::run);
        registry.add("server_setup",
                "Prepare the dev server: install it, write dev defaults, install the msc agent.",
                Schema.of().opt("version", "string", "Minecraft version. Default " + config.minecraft())
                        .opt("platform", "string", "paper, purpur, folia, fabric, quilt, neoforge, forge, minestom, "
                                + "velocity or waterfall")
                        .opt("proxy", "string", "velocity or bungeecord: backend behind that proxy; none: off")
                        .opt("servers", "strings", "Proxy: Paper backends it starts, such as lobby, game")
                        .opt("mode", "string", "build (creative, peaceful) or game (survival, normal)")
                        .opt("bedrock", "boolean", "Geyser+Floodgate for Bedrock players; false removes")
                        .opt("acceptEula", "boolean", EulaPrompts.EULA_ARG),
                setup::run);
        registry.add("world_create",
                "Add a generated world (real terrain) beside the flat dev world; after a restart build in it with "
                        + "world=msc:<name>. No name: list them.",
                Schema.of().opt("name", "string", "a-z, 0-9, _")
                        .opt("terrain", "string", "normal (default), amplified, large_biomes, caves, floating_islands, "
                                + "nether, end, flat, void")
                        .opt("biomes", "strings", "One, or several sharing the land")
                        .opt("layers", "strings", "flat: bottom up, such as bedrock, dirt:3")
                        .opt("remove", "boolean", "Drop it from the pack"),
                args -> WorldCreate.run(config, server, args));
        PluginTools plugins = new PluginTools(config, server,
                new AddonSources(AddonSources.Urls.DEFAULT, config.downloadStore()));
        registry.add("plugins",
                "Plugins or mods. search: ids on Modrinth, Hangar, SpigotMC. install: by id, with required "
                        + "dependencies. Neither: list. Each call checks the folder.",
                Schema.of().opt("search", "string", "Words")
                        .opt("install", "strings", "modrinth:<slug>, hangar:<slug>, spigot:<number>"),
                plugins::run);
        registry.add("server_start", "Start the dev server and wait until it is ready. Returns startup errors only.",
                Schema.of().range("timeoutSeconds", 1, ServerRun.MAX_START_SECONDS, "Default 180").opt("acceptEula", "boolean", EulaPrompts.EULA_ARG),
                run::start);
        registry.add("server_stop", "Stop the dev server.", Schema.of(), run::stop);
        registry.add("server_detach",
                "Keep the dev server and the pack host running after this session ends, so the user can play. "
                        + "The next session's server_start, dev_cycle or server_stop stops them.",
                Schema.of(), run::detach);
        registry.add("dev_cycle",
                "Build, deploy and restart in one call. Tests run during the restart; an unchanged "
                        + "jar skips it. Returns build, test and startup errors, and phase times.",
                Schema.of().opt("project", "string", "Project folder in the workspace. Default: the workspace")
                        .opt("restart", "boolean", "Default true. false still deploys")
                        .maxChars(),
                args -> withOpenReviews(cycle.run(args), world));
        registry.add("server_logs",
                "Read the server log. mode=errors (default): warnings and errors, traces trimmed, repeats "
                        + "collapsed. mode=tail: last lines. mode=grep: regex matches. mode=triage: why it crashed or "
                        + "failed to start, and a fix.",
                Schema.of().opt("mode", "string", "errors, tail, grep or triage")
                        .opt("pattern", "string", "Regex for mode=grep")
                        .range("lines", 1, Integer.MAX_VALUE, "For tail and grep. Default 30")
                        .opt("all", "boolean", "Whole log, not only since the last start")
                        .maxChars(),
                console::logs);
        registry.add("server_command", "Run console commands on the server and return their output.",
                Schema.of().req("command", "string", "Command without the leading slash; several: one per line; "
                                + "@<backend> first for a proxy's backend"),
                args -> {
                    String first = backups.beforeCommands(args.text("command"));
                    String out = console.command(args);
                    return first.isEmpty() ? out : out + "\n" + first;
                });
        registry.add("server_wait", "Wait until a log or chat line matches a regex, instead of polling.",
                Schema.of().req("until", "string", "Regex, case-insensitive")
                        .range("timeoutSeconds", 1, ServerConsole.MAX_WAIT_SECONDS, "Default 60"), console::await);
        ServerExport export = new ServerExport(config, server);
        registry.add("server_export", "Ship the dev server: docker (Compose on the itzg images: server jar and "
                        + "Modrinth/Spigot plugins update themselves, scheduled world backups) or a pterodactyl egg.",
                Schema.of().opt("target", "string", "docker (default) or pterodactyl")
                        .opt("dir", "string", "Empty folder in the workspace. Default export/<target>")
                        .opt("world", "boolean", "Include the world (server stopped)"),
                export::run);
        registry.add("server_profile", "Profile the running server while it is loaded: where time goes (your code, "
                        + "plugins or mods, server areas), hottest methods, GC, allocation, and config to tune.",
                Schema.of().range("seconds", 5, ServerProfile.MAX_SECONDS, "Default 30"),
                args -> ServerProfile.run(config, hub, server, args.integer("seconds", 30)));
        return backups;
    }

    /** A result, then the reviews still failing: a run that ends on dev_cycle sees them (ADR 0084). */
    static String withOpenReviews(String result, WorldService world) {
        String open = world.reviews().line();
        return open.isEmpty() ? result : result + "\n" + open;
    }
}
