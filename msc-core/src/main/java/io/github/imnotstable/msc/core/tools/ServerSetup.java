package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.FabricDownloader;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.PaperDownloader;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.server.ServerProperties;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.templates.ScaffoldResult;
import io.github.imnotstable.msc.templates.TemplateVariables;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        this.loaders = new LoaderInstaller(LoaderInstaller.Urls.DEFAULT, config.dataDir().resolve("cache"),
                config.javaCommand());
        this.eula = eula;
    }

    String run(Args args) throws IOException, InterruptedException {
        if (server.running()) {
            throw new ToolException("stop the server before server_setup");
        }
        String version = args.str("version", config.minecraft());
        boolean acceptEula = args.bool("acceptEula", false);
        ServerProperties.Mode mode;
        try {
            mode = args.has("mode") ? ServerProperties.Mode.of(args.str("mode").strip()) : null;
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        Platform platform = server.platform();
        if (args.str("platform", null) != null) {
            platform = Platform.of(args.str("platform"));
            Platform.remember(config, platform);
        }
        Path dir = config.serverDir();
        if (platform == Platform.MINESTOM) {
            Files.createDirectories(dir);
            return (mode == null ? "" : "mode ignored: a Minestom server sets game mode and difficulty in its own code\n")
                    + "minestom needs no server download and has no EULA step: the project's own jar is the server and "
                    + "the msc agent is built into it. Server directory: " + dir + "; next: dev_cycle";
        }
        StringBuilder out = new StringBuilder();
        if (Templates.ids().contains(Templates.PAPER_SERVER)) {
            int written = writeMissingDefaults(dir, acceptEula);
            if (written > 0) {
                out.append("wrote ").append(written).append(" default config files\n");
            }
        }
        if (mode != null) {
            ServerProperties.set(dir.resolve("server.properties"), mode.settings());
            out.append("mode ").append(mode.id()).append(": ").append(mode.settings().get("gamemode")).append(", ")
                    .append(mode.settings().get("difficulty")).append(" difficulty from the next start\n");
        }
        Path addons = Files.createDirectories(dir.resolve(platform.addonDir()));
        switch (platform) {
            case FABRIC -> fabric(version, dir, addons, out);
            case QUILT -> quilt(version, dir, addons, out);
            case NEOFORGE -> neoforge(version, dir, out);
            case FORGE -> forge(version, dir, out);
            case PURPUR -> report("purpur", loaders.purpur(version, dir), out);
            default -> paper(version, dir, out);
        }
        installAgent(platform, addons, out);
        eulaStep(dir.resolve("eula.txt"), acceptEula, out);
        out.append(platform.id()).append(" server ready in ").append(dir).append("; next: server_start or dev_cycle");
        return out.toString();
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
        Files.copy(d.jar(), dir.resolve(Platform.PAPER.serverJar()), StandardCopyOption.REPLACE_EXISTING);
        out.append("paper ").append(d.version()).append(" build ").append(d.build())
                .append(d.cached() ? " (cached)" : " (downloaded)").append('\n');
    }

    /** Fabric: the server launcher plus the Fabric API mod, at the versions the fabric-mod template pins. */
    private void fabric(String version, Path dir, Path mods, StringBuilder out) throws IOException, InterruptedException {
        String loader = pinned("fabricLoaderVersion", "Fabric Loader");
        String api = fabricApi(version);
        FabricDownloader.Download launcher = fabric.launcher(version, loader);
        Files.copy(launcher.jar(), dir.resolve(Platform.FABRIC.serverJar()), StandardCopyOption.REPLACE_EXISTING);
        out.append("fabric launcher for ").append(version).append(", loader ").append(loader)
                .append(launcher.cached() ? " (cached)" : " (downloaded)")
                .append("; it downloads the Minecraft server on first start\n");
        FabricDownloader.Download apiJar = fabric.api(api);
        Files.copy(apiJar.jar(), mods.resolve("fabric-api.jar"), StandardCopyOption.REPLACE_EXISTING);
        out.append("fabric api ").append(api).append(apiJar.cached() ? " (cached)" : " (downloaded)").append('\n');
    }

    /** Quilt: its launcher, plus Fabric API, which Quilt loads like any Fabric mod. The Fabric agent runs on it. */
    private void quilt(String version, Path dir, Path mods, StringBuilder out) throws IOException, InterruptedException {
        String loader = pinned("quiltLoaderVersion", "Quilt");
        String api = fabricApi(version);
        report("quilt loader", loaders.quilt(version, loader, pinned("quiltInstallerVersion", "Quilt"), dir), out);
        FabricDownloader.Download apiJar = fabric.api(api);
        Files.copy(apiJar.jar(), mods.resolve("fabric-api.jar"), StandardCopyOption.REPLACE_EXISTING);
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
            Files.copy(agent, addons.resolve(installedAgentName(platform)), StandardCopyOption.REPLACE_EXISTING);
            out.append("agent installed in ").append(platform.addonDir()).append("/\n");
        } else {
            out.append("warning: msc ").append(platform.id()).append(" agent not found, so world and build tools will "
                    + "not work. Put its jar in ").append(config.dataDir().resolve("agent"))
                    .append(" or set MSC_AGENT_JAR, then run server_setup again\n");
        }
    }

    private void eulaStep(Path file, boolean accept, StringBuilder out) throws IOException {
        if (accept && Files.exists(file) && !ServerManager.eulaAccepted(file)) {
            Files.writeString(file, Files.readString(file).replaceAll("(?m)^eula=false[ \t]*$", "eula=true"));
        }
        if (accept) {
            out.append(eula.recordForMachine());
        }
        Optional<MachineEula.Acceptance> machine = MachineEula.applyTo(config.eulaFile(), file);
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

    /** Named for the agent, not the platform: Quilt runs {@code msc-agent-fabric.jar}, Purpur {@code msc-agent-paper.jar}. */
    static String installedAgentName(Platform platform) {
        return "msc-agent-" + platform.agent() + ".jar";
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
