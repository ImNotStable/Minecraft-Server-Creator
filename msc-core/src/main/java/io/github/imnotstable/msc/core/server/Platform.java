package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The server platform a workspace targets. It decides what {@code server_setup} installs, how the server
 * starts and where {@code dev_cycle} puts the built jar. See ADR 0011.
 */
public enum Platform {
    /** Paper jar, plugins in {@code plugins/}. */
    PAPER("Paper", "paper.jar", "plugins", true, "paper", Packages.PAPER),
    /** Purpur, a Paper fork: Paper plugins and the Paper agent run on it unchanged. See ADR 0027. */
    PURPUR("Purpur", "purpur.jar", "plugins", true, "paper", Packages.with(Packages.PAPER, "org.purpurmc.")),
    /**
     * Folia, Paper with regionised multithreading: Paper plugins that declare {@code folia-supported: true} run on it.
     * The Paper agent runs server operations only there; world operations are not offered (ADR 0045).
     */
    FOLIA("Folia", "folia.jar", "plugins", true, "paper", Packages.PAPER),
    /** Fabric server launcher, mods in {@code mods/}. */
    FABRIC("Fabric", "fabric-server-launch.jar", "mods", true, "fabric", Packages.FABRIC),
    /** Quilt server launcher; Quilt loads Fabric mods, so the Fabric agent and Fabric API run on it. See ADR 0027. */
    QUILT("Quilt", "quilt-server-launch.jar", "mods", true, "fabric", Packages.with(Packages.FABRIC, "org.quiltmc.")),
    /** NeoForge's server starter jar (it reads the installer's argument files), mods in {@code mods/}. */
    NEOFORGE("NeoForge", "server.jar", "mods", true, "neoforge", List.of("net.minecraft.", "com.mojang.", "net.neoforged.",
            "cpw.mods.", "org.spongepowered.")),
    /** Minecraft Forge's runnable shim jar, copied to a fixed name, mods in {@code mods/}. */
    FORGE("Forge", "forge-server.jar", "mods", true, "forge", List.of("net.minecraft.", "com.mojang.",
            "net.minecraftforge.", "cpw.mods.", "org.spongepowered.")),
    /** The project's own runnable jar is the server; the agent is built into it. No Mojang EULA step. */
    MINESTOM("Minestom", "server.jar", null, false, "minestom", List.of("net.minestom.", "net.kyori.")),
    /**
     * The Velocity proxy, plugins in {@code plugins/}. Not a Minecraft server: no world, no msc agent and no EULA step;
     * players reach a Paper backend through it (ADR 0045).
     */
    VELOCITY("Velocity", "velocity.jar", "plugins", false, "velocity", List.of("com.velocitypowered.", "net.kyori.")),
    /**
     * The Waterfall proxy, PaperMC's BungeeCord fork (checksummed on Fill, which BungeeCord's own Jenkins is not): it
     * runs BungeeCord plugins, plugins in {@code plugins/}. A proxy like Velocity (ADR 0088).
     */
    WATERFALL("Waterfall", "waterfall.jar", "plugins", false, "waterfall", List.of("net.md_5.", "io.github.waterfallmc."));

    private final String displayName;
    private final String serverJar;
    private final String addonDir;
    private final boolean eula;
    private final String agent;
    private final List<String> packages;

    Platform(String displayName, String serverJar, String addonDir, boolean eula, String agent, List<String> packages) {
        this.displayName = displayName;
        this.serverJar = serverJar;
        this.addonDir = addonDir;
        this.eula = eula;
        this.agent = agent;
        this.packages = packages;
    }

    /** Package lists more than one platform shares; a holder, since enum constants cannot read the enum's statics. */
    private static final class Packages {
        /** A Paper server's: Minecraft's, Bukkit's, Spigot's, Paper's and the libraries it ships as its own API. */
        static final List<String> PAPER = List.of("net.minecraft.", "com.mojang.", "org.bukkit.", "org.spigotmc.",
                "io.papermc.", "com.destroystokyo.", "co.aikar.", "ca.spottedleaf.", "net.md_5.", "net.kyori.");
        /** A Fabric server's: Minecraft's, Fabric's (loader and API) and Mixin's. */
        static final List<String> FABRIC = List.of("net.minecraft.", "com.mojang.", "net.fabricmc.",
                "org.spongepowered.");

        static List<String> with(List<String> packages, String more) {
            return java.util.stream.Stream.concat(packages.stream(), java.util.stream.Stream.of(more)).toList();
        }
    }

    /**
     * The package prefixes of the server's own code, such as {@code com.velocitypowered.} for Velocity: a stack frame
     * there is the server's, never the project's ({@link LogCondenser}, #592). Every platform must name its own.
     */
    public List<String> packages() {
        return packages;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The platform's own spelling, such as {@code NeoForge}, for text the user or model reads. */
    public String displayName() {
        return displayName;
    }

    /** File name of the jar the server starts from, inside the server directory. */
    public String serverJar() {
        return serverJar;
    }

    /** Folder in the server directory that plugins or mods go in, or null when the project jar is the server. */
    public String addonDir() {
        return addonDir;
    }

    /**
     * Which msc agent runs on this platform, and so also which API and project layout it shares: {@code paper},
     * {@code fabric}, {@code neoforge}, {@code forge} or {@code minestom}. Purpur and Folia share Paper's, Quilt
     * Fabric's. The proxies have none: {@code velocity} and {@code waterfall} name only their API and project layout.
     */
    public String agent() {
        return agent;
    }

    /**
     * File name server_setup installs the agent under in {@link #addonDir()}. Named for the agent, not the platform:
     * Quilt runs {@code msc-agent-fabric.jar}, Purpur {@code msc-agent-paper.jar}.
     */
    public String agentJar() {
        return "msc-agent-" + agent + ".jar";
    }

    /** True for a proxy: it runs no world and no msc agent, so world, build and player tools do not apply. */
    public boolean proxy() {
        return this == VELOCITY || this == WATERFALL;
    }

    /** The console command that stops the server: BungeeCord's is {@code end}, every other platform's {@code stop}. */
    public String stopCommand() {
        return this == WATERFALL ? "end" : "stop";
    }

    /** The one line world, build and player tools answer with on a proxy, or null where they work. */
    public String noWorldTools() {
        return proxy() ? displayName + " is a proxy with no world or msc agent: world, build and player tools do not "
                + "apply; use them in the backend's workspace" : null;
    }

    /**
     * True when {@code server_setup} installs the server this platform runs ({@link #serverJar()}), so a project for it
     * runs only once that is done; false for Minestom, whose server is the project's own jar that {@code dev_cycle}
     * deploys ({@link #addonDir()} null). Independent of the EULA: Velocity needs the setup and no EULA (#627).
     */
    public boolean needsServerSetup() {
        return addonDir != null;
    }

    /**
     * True when the server reads its resource pack from {@code server.properties}, so MSC offers the pack there and a
     * restart outside MSC still sends it (ADR 0017). Minestom sends only the pack MSC's agent hands it, and Velocity is
     * a proxy: neither reads that file for a pack, so nothing is written there for them ({@code PackProperties}, #718).
     */
    public boolean readsPackProperties() {
        return switch (this) {
            case PAPER, PURPUR, FOLIA, FABRIC, QUILT, NEOFORGE, FORGE -> true;
            case MINESTOM, VELOCITY, WATERFALL -> false;
        };
    }

    /** True when the server is Mojang's and so needs an accepted {@code eula.txt}. */
    public boolean needsEula() {
        return eula;
    }

    /**
     * Display names of the platforms that pass {@code filter}, as prose: {@code "Paper, Fabric or Minestom"} with
     * {@code conjunction} "or".
     */
    public static String names(Predicate<Platform> filter, String conjunction) {
        List<String> names = Arrays.stream(values()).filter(filter).map(Platform::displayName).toList();
        if (names.size() < 2) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " " + conjunction + " " + names.getLast();
    }

    public static String ids() {
        return Arrays.stream(values()).map(Platform::id).collect(Collectors.joining(", "));
    }

    public static Platform of(String id) {
        for (Platform p : values()) {
            if (p.id().equalsIgnoreCase(id.strip())) {
                return p;
            }
        }
        throw new ToolException("unknown platform " + id + ". Available: " + ids());
    }

    /** {@code .msc/platform}: what the workspace was scaffolded or set up as. */
    public static Path file(CoreConfig config) {
        return config.dataDir().resolve("platform");
    }

    public static void remember(CoreConfig config, Platform platform) throws IOException {
        AtomicFile.write(file(config), to -> Files.writeString(to, platform.id() + "\n"));
    }

    /** The remembered platform, else what the workspace's project files say, else Paper. */
    public static Platform current(CoreConfig config) {
        Path file = file(config);
        if (Files.isRegularFile(file)) {
            try {
                return of(Files.readString(file));
            } catch (IOException | ToolException e) {
                // Unreadable or hand-edited to nonsense: fall back to looking at the project.
            }
        }
        Platform detected = detect(config.workspace());
        return detected != null ? detected : PAPER;
    }

    /**
     * The platform whose server runs a project that runs on {@code project} ({@link #runsOn}, first the one it is
     * made for): the workspace's remembered one when it runs the same projects as any of them ({@link #agent()}
     * equal: Quilt for a Fabric mod, Purpur or Folia for a Paper plugin, Velocity for a jar that is also a Velocity
     * plugin), else the project's first. {@code dev_cycle} deploys to it and {@code project_scaffold} checks its
     * server (#631, #642).
     */
    public static Platform serving(CoreConfig config, List<Platform> project) {
        if (!Files.exists(file(config))) {
            return project.getFirst();
        }
        Platform workspace = current(config);
        return project.stream().anyMatch(p -> p.agent().equals(workspace.agent())) ? workspace : project.getFirst();
    }

    /**
     * The workspace's remembered platform when its server runs none of {@code project} ({@link #serving}: another
     * agent family, such as a Fabric mod in a Paper workspace), else null. {@code dev_cycle} then refuses to deploy,
     * as that server would never load the project, and {@code project_scaffold} names {@code server_setup platform=}
     * to switch the workspace (#636, ADR 0048).
     */
    public static Platform otherFamily(CoreConfig config, List<Platform> project) {
        if (!Files.exists(file(config))) {
            return null;
        }
        Platform workspace = current(config);
        return serving(config, project) == workspace ? null : workspace;
    }

    /**
     * What a project says about the platform: a descriptor file the server loads ({@code file}, under any of
     * {@link #RESOURCE_ROOTS}), or build-file text that {@code build} finds: a plugin id, a DSL block or a dependency.
     * {@code texts} is what it looks for, as a project writes it: the file, the words, or block lines like {@code paper {}.
     */
    record Sign(Platform platform, String file, List<String> texts, Pattern build) {
        static Sign descriptor(Platform platform, String file) {
            return new Sign(platform, file, List.of(file), null);
        }

        /** Any of {@code words} anywhere in a build file, where no other platform's longer sign covers it. */
        static Sign build(Platform platform, String... words) {
            return new Sign(platform, null, List.of(words), Pattern.compile(Arrays.stream(words).map(Pattern::quote)
                    .collect(Collectors.joining("|"))));
        }

        /** A line that opens one of the DSL blocks {@code names}, such as {@code paper {}}. */
        static Sign blocks(Platform platform, String... names) {
            return new Sign(platform, null, Arrays.stream(names).map(name -> name + " {").toList(),
                    Pattern.compile("(?m)^\\s*(?:" + String.join("|", names) + ")\\s*\\{"));
        }

        /** Each place this build sign's text is in {@code buildText}; none for a descriptor. */
        Stream<Found> found(String buildText) {
            return build == null ? Stream.empty()
                    : build.matcher(buildText).results().map(match -> new Found(this, match.start(), match.end()));
        }
    }

    /** A build sign's text at {@code [start, end)} of a project's build text. */
    private record Found(Sign sign, int start, int end) {
        /** True when {@code other} is another platform's sign whose longer text covers this one, so this is its. */
        boolean coveredBy(Found other) {
            return other.sign.platform() != sign.platform() && other.start <= start && end <= other.end
                    && other.end - other.start > end - start;
        }
    }

    /**
     * Where a project keeps the descriptor the server loads: resources, or templates its build expands into them
     * (the NeoForge ModDevGradle MDK keeps {@code META-INF/neoforge.mods.toml} in {@code src/main/templates}).
     */
    private static final List<String> RESOURCE_ROOTS = List.of("src/main/resources", "src/main/templates");

    /** The build files {@link #detect} reads: plugin ids, DSL blocks and dependencies live there. */
    private static final List<String> BUILD_FILES = List.of("build.gradle.kts", "build.gradle", "gradle/libs.versions.toml");

    /**
     * The signs {@link #detect} reads, first match wins (#593, #599), in three tiers:
     * <ol>
     *   <li>the descriptor the server loads: the file, or the Gradle plugin or block that writes it at build time
     *       (plugin-yml's {@code bukkit {}} and {@code paper {}}, resource-factory's conventions);</li>
     *   <li>the platform's own Gradle plugin: Loom, ModDevGradle, NeoGradle, ForgeGradle, paperweight, run-paper or
     *       run-velocity, which build or run for that platform only;</li>
     *   <li>a dependency word: {@code velocity-api}, then Minestom.</li>
     * </ol>
     * A build file also names the APIs of other platforms a jar is built for: a plugin built for Paper and Velocity has
     * {@code plugin.yml} (or a {@code paper {}} block) and names {@code velocity-api}, so the bare word comes last.
     * Within a tier a game server comes before the proxy, so a jar that is both a Paper and a Velocity plugin is Paper
     * here; {@link #runsOn} names both, so a Velocity workspace runs it too (#642). Quilt comes before Fabric, whose
     * mods it loads.
     * <p>
     * A build sign's text counts only where no longer sign of another platform covers it, whatever the order:
     * ModDevGradle's legacyforge plugin id (Forge) extends its NeoForge one, and is Forge's alone (#648).
     */
    static final List<Sign> SIGNS = List.of(
            Sign.descriptor(QUILT, "quilt.mod.json"),
            Sign.descriptor(FABRIC, "fabric.mod.json"),
            Sign.build(FABRIC, "xyz.jpenilla.resource-factory-fabric-convention"),
            Sign.descriptor(NEOFORGE, "META-INF/neoforge.mods.toml"),
            Sign.build(NEOFORGE, "xyz.jpenilla.resource-factory-neoforge-convention"),
            Sign.descriptor(FORGE, "META-INF/mods.toml"),
            Sign.descriptor(PAPER, "paper-plugin.yml"),
            Sign.descriptor(PAPER, "plugin.yml"),
            Sign.build(PAPER, "plugin-yml.bukkit", "plugin-yml.paper", "xyz.jpenilla.resource-factory-bukkit-convention",
                    "xyz.jpenilla.resource-factory-paper-convention"),
            Sign.blocks(PAPER, "bukkit", "paper", "bukkitpluginyaml", "paperpluginyaml"),
            Sign.descriptor(VELOCITY, "velocity-plugin.json"),
            Sign.build(VELOCITY, "xyz.jpenilla.resource-factory-velocity-convention"),
            Sign.build(QUILT, "org.quiltmc.loom"),
            Sign.build(FABRIC, "fabric-loom"),
            Sign.build(FORGE, "net.neoforged.moddev.legacyforge"),
            Sign.build(NEOFORGE, "net.neoforged.moddev", "net.neoforged.gradle"),
            Sign.build(FORGE, "net.minecraftforge.gradle"),
            Sign.build(PAPER, "io.papermc.paperweight.userdev", "xyz.jpenilla.run-paper"),
            Sign.build(VELOCITY, "xyz.jpenilla.run-velocity"),
            Sign.build(VELOCITY, "velocity-api"),
            Sign.descriptor(WATERFALL, "bungee.yml"),
            Sign.build(WATERFALL, "xyz.jpenilla.run-waterfall"),
            Sign.build(WATERFALL, "waterfall-api", "bungeecord-api"),
            Sign.build(MINESTOM, "minestom"));

    /** Platform of the Gradle project in {@code project}, or null when it does not say: the first of {@link #runsOn}. */
    public static Platform detect(Path project) {
        List<Platform> runsOn = runsOn(project);
        return runsOn.isEmpty() ? null : runsOn.getFirst();
    }

    /**
     * Every platform whose server loads the Gradle project in {@code project}, the one {@link #detect} names first;
     * empty when it does not say. A jar can be several platforms' plugin or mod ({@code plugin.yml} and
     * {@code velocity-plugin.json}), but never also a server whose project jar is the server (Minestom): that counts
     * only when nothing else shows.
     */
    public static List<Platform> runsOn(Path project) {
        String build = buildText(project);
        List<Found> texts = SIGNS.stream().flatMap(sign -> sign.found(build)).toList();
        List<Platform> found = new ArrayList<>();
        for (Sign sign : SIGNS) {
            if (!found.contains(sign.platform()) && shows(sign, project, texts)) {
                found.add(sign.platform());
            }
        }
        if (found.size() > 1) {
            found.removeIf(p -> !p.needsServerSetup());
        }
        return List.copyOf(found);
    }

    /** True when the project has the sign's descriptor, or its build text where no other platform's sign covers it. */
    private static boolean shows(Sign sign, Path project, List<Found> texts) {
        if (sign.file() != null) {
            return RESOURCE_ROOTS.stream().anyMatch(root -> Files.isRegularFile(project.resolve(root).resolve(sign.file())));
        }
        return texts.stream().anyMatch(text -> text.sign() == sign && texts.stream().noneMatch(text::coveredBy));
    }

    /** The project's build files, lower-cased and joined; a file that is missing or not readable as text adds nothing. */
    private static String buildText(Path project) {
        StringBuilder text = new StringBuilder();
        for (String build : BUILD_FILES) {
            Path file = project.resolve(build);
            try {
                if (Files.isRegularFile(file)) {
                    text.append(Files.readString(file).toLowerCase(Locale.ROOT)).append('\n');
                }
            } catch (IOException e) {
                // Not readable as text: no opinion.
            }
        }
        return text.toString();
    }

    /** The command that starts this platform's server from its directory. */
    public List<String> launchCommand(String java) {
        return switch (this) {
            case PAPER, PURPUR, FOLIA -> List.of(java, "-Xmx2G", "-jar", serverJar, "--nogui");
            case FABRIC, QUILT, NEOFORGE, FORGE -> List.of(java, "-Xmx2G", "-jar", serverJar, "nogui");
            case MINESTOM -> List.of(java, "-Xmx1G", "-jar", serverJar);
            case VELOCITY, WATERFALL -> List.of(java, "-Xmx512M", "-jar", serverJar);
        };
    }
}
