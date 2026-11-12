package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.FabricDownloader;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code server_setup} for each platform, and where the msc agent jar for a platform comes from. */
final class ServerSetup {
    private final CoreConfig config;
    private final ServerManager server;
    private final PaperDownloader paper;
    private final FabricDownloader fabric;
    private final EulaPrompts eula;

    ServerSetup(CoreConfig config, ServerManager server, PaperDownloader paper, FabricDownloader fabric,
                EulaPrompts eula) {
        this.config = config;
        this.server = server;
        this.paper = paper;
        this.fabric = fabric;
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
        if (isEmpty(dir) && Templates.ids().contains(Templates.PAPER_SERVER)) {
            // paper-server holds plain vanilla server config (server.properties, eula.txt), so Fabric uses it too.
            ScaffoldResult defaults = Templates.scaffold(Templates.PAPER_SERVER, dir,
                    TemplateVariables.of("server").withAcceptEula(acceptEula));
            out.append("wrote ").append(defaults.files().size()).append(" default config files\n");
        }
        if (mode != null) {
            ServerProperties.set(dir.resolve("server.properties"), mode.settings());
            out.append("mode ").append(mode.id()).append(": ").append(mode.settings().get("gamemode")).append(", ")
                    .append(mode.settings().get("difficulty")).append(" difficulty from the next start\n");
        }
        Path addons = Files.createDirectories(dir.resolve(platform.addonDir()));
        if (platform == Platform.FABRIC) {
            fabric(version, dir, addons, out);
        } else {
            paper(version, dir, out);
        }
        installAgent(platform, addons, out);
        eulaStep(dir.resolve("eula.txt"), acceptEula, out);
        out.append(platform.id()).append(" server ready in ").append(dir).append("; next: server_start or dev_cycle");
        return out.toString();
    }

    private static boolean isEmpty(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return true;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.findAny().isEmpty();
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

    static String installedAgentName(Platform platform) {
        return "msc-agent-" + platform.id() + ".jar";
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

    private static Path agentIn(Path dir, Platform platform) throws IOException {
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
            return jars.stream().filter(jar -> Arrays.stream(Platform.values())
                    .noneMatch(other -> jar.getFileName().toString().contains(other.id()))).findFirst().orElse(null);
        }
    }
}
