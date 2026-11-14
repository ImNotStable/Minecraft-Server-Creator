package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.server.ServerFiles;
import io.github.imnotstable.msc.core.server.ServerProperties;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.templates.ScaffoldResult;
import io.github.imnotstable.msc.templates.TemplateVariables;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code server_setup} for each platform, and where the msc agent jar for a platform comes from. */
final class ServerSetup {
    private final CoreConfig config;
    private final ServerManager server;
    private final PaperDownloader paper;
    private final FabricDownloader fabric;
    private final LoaderInstaller loaders;
    private final EulaPrompts eula;

    ServerSetup(CoreConfig config, ServerManager server, PaperDownloader paper, FabricDownloader fabric,
                EulaPrompts eula) {
        this.config = config;
        this.server = server;
        this.paper = paper;
        this.fabric = fabric;
        this.loaders = new LoaderInstaller(config.loaderUrls(), config.dataDir().resolve("cache"),
                config.javaCommand());
        this.eula = eula;
    }

    /** What a Minecraft or loader version can be, so it goes into a download URL as it is. */
    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    String run(Args args) throws IOException, InterruptedException {
        if (server.running()) {
            throw new ToolException("stop the server before server_setup");
        }
        // The setup would replace the files of another live session's server (ADR 0037).
        server.checkNoOtherSession();
        String version = args.str("version", config.minecraft()).strip();
        if (!VERSION.matcher(version).matches()) {
            throw new ToolException("version must look like " + config.minecraft()
                    + " (letters, digits, '.', '-', '_' and '+'), not \"" + version + "\"");
        }
        boolean acceptEula = args.bool("acceptEula", false);
        ServerProperties.Mode mode;
        try {
            mode = args.has("mode") ? ServerProperties.Mode.of(args.str("mode").strip()) : null;
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        // Chosen here, remembered for the workspace only once the setup succeeded: a refused or failed setup
        // (unsupported version, download error) leaves the workspace as it was, its working server included.
        Platform chosen = args.str("platform", null) == null ? null : Platform.of(args.str("platform"));
        Platform platform = chosen == null ? server.platform() : chosen;
        Path dir = OutputFile.checkFolder(config.serverDir(), "server folder");
        Steps steps = new Steps("server_setup");
        String rememberFails = "the server folder is set up, but the workspace platform is still "
                + Platform.current(config).id() + ": run server_setup again";
        if (platform == Platform.MINESTOM) {
            steps.then(CREATE_FOLDER);
            if (chosen != null) {
                steps.then(REMEMBER).ifFails(REMEMBER, rememberFails);
            }
            steps.run(CREATE_FOLDER, () -> Files.createDirectories(dir));
            remember(steps, chosen);
            return (mode == null ? "" : "mode ignored: a Minestom server sets game mode and difficulty in its own code\n")
                    + "minestom needs no server download and has no EULA step: the project's own jar is the server and "
                    + "the msc agent is built into it. Server directory: " + dir + "; next: dev_cycle";
        }
        // The fallible part first (version checks, downloads, installers); the rest of run/ only once it is through.
        boolean defaults = Templates.ids().contains(Templates.PAPER_SERVER);
        steps.then(INSTALL);
        if (defaults) {
            steps.then(DEFAULTS);
        }
        if (mode != null) {
            steps.then(MODE);
        }
        steps.then(AGENT).then(EULA);
        if (chosen != null) {
            steps.then(REMEMBER).ifFails(REMEMBER, rememberFails);
        }
        String partly = "server_setup again finishes it";
        for (String step : List.of(DEFAULTS, MODE, AGENT, EULA)) {
            steps.ifFails(step, "the server is installed but not fully set up; " + partly);
        }
        StringBuilder installed = new StringBuilder();
        steps.run(INSTALL, () -> {
            switch (platform) {
                case FABRIC -> fabric(version, dir, installed);
                case QUILT -> quilt(version, dir, installed);
                case NEOFORGE -> neoforge(version, dir, installed);
                case FORGE -> forge(version, dir, installed);
                case PURPUR -> report("purpur", loaders.purpur(version, dir), installed);
                default -> paper(version, dir, installed);
            }
        });
        StringBuilder out = new StringBuilder();
        if (defaults) {
            int written = steps.run(DEFAULTS, () -> writeMissingDefaults(dir, acceptEula));
            if (written > 0) {
                out.append("wrote ").append(written).append(" default config files\n");
            }
        }
        if (mode != null) {
            steps.run(MODE, () -> ServerPropertiesFile.set(dir.resolve(ServerPropertiesFile.NAME), mode.settings()));
            out.append("mode ").append(mode.id()).append(": ").append(mode.settings().get("gamemode")).append(", ")
                    .append(mode.settings().get("difficulty")).append(" difficulty from the next start\n");
        }
        out.append(installed);
        // The addon folder exists after setup even without an agent, for the deploys that follow.
        steps.run(AGENT, () -> installAgent(platform, Files.createDirectories(dir.resolve(platform.addonDir())), out));
        steps.run(EULA, () -> eulaStep(dir.resolve("eula.txt"), acceptEula, out));
        remember(steps, chosen);
        out.append(platform.id()).append(" server ready in ").append(dir).append("; next: server_start or dev_cycle");
        return out.toString();
    }

    static final String CREATE_FOLDER = "create the server folder";
    static final String INSTALL = "download and install the server";
    static final String DEFAULTS = "write missing default config files";
    static final String MODE = "set the game mode in " + ServerPropertiesFile.NAME;
    static final String AGENT = "install the msc agent";
    static final String EULA = "apply the EULA answer";
    static final String REMEMBER = "remember the workspace platform";

    /** Last: a refused or failed setup leaves the workspace's platform, and with it its working server, as it was. */
    private void remember(Steps steps, Platform chosen) throws InterruptedException {
        if (chosen != null) {
            steps.run(REMEMBER, () -> Platform.remember(config, chosen));
        }
    }

    /**
     * Writes each paper-server default (plain vanilla config: server.properties, eula.txt, so every platform uses it)
     * that {@code dir} lacks. Per file, not per folder: dev_cycle or server_start acceptEula=true may have put
     * something in {@code run/} before the first server_setup.
     */
    private int writeMissingDefaults(Path dir, boolean acceptEula) throws IOException {
        Path staging = Files.createTempDirectory(Files.createDirectories(config.dataDir()), "server-defaults-");
        try {
            ScaffoldResult defaults = Templates.scaffold(Templates.PAPER_SERVER, staging.resolve("server"),
                    TemplateVariables.of("server").withAcceptEula(acceptEula));
            int written = 0;
            for (Path file : defaults.files()) {
                Path target = dir.resolve(defaults.root().relativize(file).toString());
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.move(file, target);
                    written++;
                }
            }
            return written;
        } finally {
            Scaffold.deleteTree(staging);
        }
    }

    private void paper(String version, Path dir, StringBuilder out) throws IOException, InterruptedException {
        PaperDownloader.Download d = paper.download(version);
        ServerFiles.place(d.jar(), dir, Platform.PAPER.serverJar());
        out.append("paper ").append(d.version()).append(" build ").append(d.build())
                .append(d.cached() ? " (cached)" : " (downloaded)").append('\n');
    }

    /** Fabric: the server launcher plus the Fabric API mod, at the versions the fabric-mod template pins. */
    private void fabric(String version, Path dir, StringBuilder out) throws IOException, InterruptedException {
        String loader = pinned("fabricLoaderVersion", "Fabric Loader");
        String api = fabricApi(version);
        FabricDownloader.Download launcher = fabric.launcher(version, loader);
        FabricDownloader.Download apiJar = fabric.api(api);
        ServerFiles.place(launcher.jar(), dir, Platform.FABRIC.serverJar());
        out.append("fabric launcher for ").append(version).append(", loader ").append(loader)
                .append(launcher.cached() ? " (cached)" : " (downloaded)")
                .append("; it downloads the Minecraft server on first start\n");
        installFabricApi(api, apiJar, dir.resolve(Platform.FABRIC.addonDir()), out);
    }

    /** Quilt: its launcher, plus Fabric API, which Quilt loads like any Fabric mod. The Fabric agent runs on it. */
    private void quilt(String version, Path dir, StringBuilder out) throws IOException, InterruptedException {
        String loader = pinned("quiltLoaderVersion", "Quilt");
        String api = fabricApi(version);
        FabricDownloader.Download apiJar = fabric.api(api);
        report("quilt loader", loaders.quilt(version, loader, pinned("quiltInstallerVersion", "Quilt"), dir), out);
        installFabricApi(api, apiJar, dir.resolve(Platform.QUILT.addonDir()), out);
    }

    private static void installFabricApi(String api, FabricDownloader.Download apiJar, Path mods, StringBuilder out)
            throws IOException {
        ScanFolders.copy(apiJar.jar(), mods.resolve("fabric-api.jar"), mods.getParent());
        out.append("fabric api ").append(api).append(apiJar.cached() ? " (cached)" : " (downloaded)").append('\n');
    }

    private void neoforge(String version, Path dir, StringBuilder out) throws IOException, InterruptedException {
        String neoforge = pinned("neoforgeVersion", "NeoForge");
        if (!neoforge.startsWith(version + ".")) {
            throw new ToolException("NeoForge setup supports Minecraft " + neoforge.substring(0, neoforge.lastIndexOf('.'))
                    + " (the pinned NeoForge is " + neoforge + "), not " + version);
        }
        report("neoforge", loaders.neoforge(neoforge, dir), out);
    }

    private void forge(String version, Path dir, StringBuilder out) throws IOException, InterruptedException {
        String forge = pinned("forgeVersion", "Forge");
        if (!forge.startsWith(version + "-")) {
            throw new ToolException("Forge setup supports Minecraft " + forge.substring(0, forge.indexOf('-'))
                    + " (the pinned Forge is " + forge + "), not " + version);
        }
        report("forge", loaders.forge(forge, dir), out);
    }

    private static String pinned(String key, String what) {
        String v = Templates.versions().get(key);
        if (v == null) {
            throw new ToolException("this build of msc-core has no " + what + " version pinned");
        }
        return v;
    }

    private static String fabricApi(String version) {
        String api = pinned("fabricApiVersion", "Fabric API");
        if (!api.endsWith("+" + version)) {
            throw new ToolException("Fabric setup supports Minecraft " + api.substring(api.indexOf('+') + 1)
                    + " (the pinned Fabric API is " + api + "), not " + version);
        }
        return api;
    }

    private static void report(String what, LoaderInstaller.Result r, StringBuilder out) {
        out.append(what).append(' ').append(r.version()).append(r.cached() ? " (already there)" : " (installed)")
                .append('\n');
    }

    private void installAgent(Platform platform, Path addons, StringBuilder out) throws IOException {
        Path agent = findAgentJar(platform);
        if (agent != null) {
            // Through a temp file outside the addon folder, where a loader could load it (#417).
            ScanFolders.copy(agent, addons.resolve(platform.agentJar()), addons.getParent());
            out.append("agent installed in ").append(platform.addonDir()).append("/\n");
        } else {
            out.append(missingAgent(platform, config.dataDir().resolve("agent"))).append('\n');
        }
    }

    /**
     * The warning when no agent jar was found, naming the agent the platform runs ({@link Platform#agent()}) and the
     * jar a build produces for it, never the platform: Quilt runs the Fabric agent, Purpur the Paper one.
     */
    static String missingAgent(Platform platform, Path agentDir) {
        String agent = platform.agent();
        String shared = agent.equals(platform.id()) ? "" : " (" + platform.displayName() + " runs the " + agent + " agent)";
        return "warning: msc " + agent + " agent not found" + shared + ", so world and build tools will not work. Put "
                + platform.agentJar() + " in " + agentDir + " or set MSC_AGENT_JAR, then run server_setup again";
    }

    private void eulaStep(Path file, boolean accept, StringBuilder out) throws IOException {
        if (accept && Files.exists(file) && !ServerManager.eulaAccepted(file)) {
            Files.writeString(file, Files.readString(file).replaceAll("(?m)^eula=false[ \t]*$", "eula=true"));
        }
        if (accept) {
            out.append(eula.recordForMachine());
        }
        Optional<MachineEula.Acceptance> machine = config.machineEula().applyTo(file);
        if (machine.isPresent()) {
            out.append("eula.txt has eula=true from the owner's one-time acceptance for this machine (")
                    .append(machine.get().describe()).append(")\n");
        } else if (!ServerManager.eulaAccepted(file)) {
            out.append("the Minecraft EULA is not accepted, so server_start refuses. Next: ")
                    .append(EulaPrompts.EULA_QUESTION).append(eula.oneTimeHint())
                    .append("\nOn yes: server_setup acceptEula=true (or set eula=true in ").append(file).append(")\n");
        } else {
            out.append("note: eula.txt has eula=true; running the server means the user agrees to https://aka.ms/MinecraftEULA\n");
        }
    }

    /**
     * The agent jar for a platform: the configured one, else the best match in {@code .msc/agent}, else the one
     * shipped in the core's install folder ({@code agents/}).
     */
    Path findAgentJar(Platform platform) throws IOException {
        if (config.agentJar() != null) {
            return Files.exists(config.agentJar()) ? config.agentJar() : null;
        }
        Path found = agentIn(config.dataDir().resolve("agent"), platform);
        if (found == null && config.installDir() != null) {
            found = agentIn(config.installDir().resolve("agents"), platform);
        }
        return found;
    }

    /** Whole words only, so {@code msc-agent-neoforge.jar} is not taken for Forge. */
    static boolean namesAgent(Path jar, String agent) {
        return Arrays.asList(jar.getFileName().toString().split("[-_.]")).contains(agent);
    }

    private static Path agentIn(Path dir, Platform platform) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> jars = list.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
            for (Path jar : jars) {
                if (namesAgent(jar, platform.agent())) {
                    return jar;
                }
            }
            // A jar named for another platform would not load; an unmarked one is taken on trust.
            return jars.stream().filter(jar -> Arrays.stream(Platform.values())
                    .noneMatch(other -> namesAgent(jar, other.agent()))).findFirst().orElse(null);
        }
    }
}
