package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
    VELOCITY("Velocity", "velocity.jar", "plugins", false, "velocity", List.of("com.velocitypowered.", "net.kyori."));

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
     * Fabric's. Velocity has none: {@code velocity} names only its API and project layout.
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
        return this == VELOCITY;
    }

    /** The one line world, build and player tools answer with on a proxy, or null where they work. */
    public String noWorldTools() {
        return proxy() ? displayName + " is a proxy with no world or msc agent: world, build and player tools do not "
                + "apply; use them in the backend's workspace" : null;
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
     * What a project file says about the platform: a resource file the server loads ({@code file} under
     * {@code src/main/resources}), or a word in a build file ({@code word}).
     */
    private record Sign(String file, String word, Platform platform) {
        static Sign resource(String file, Platform platform) {
            return new Sign(file, null, platform);
        }

        static Sign build(String word, Platform platform) {
            return new Sign(null, word, platform);
        }

        boolean shows(Path project) {
            return file != null ? Files.isRegularFile(project.resolve("src/main/resources").resolve(file))
                    : mentions(project, word);
        }
    }

    /**
     * The signs {@link #detect} reads, first match wins (#593). Every resource file comes before every build-file word,
     * since the file is what the server actually loads, while a build file also names the APIs of other platforms a
     * jar is built for: a plugin built for Paper and Velocity has {@code plugin.yml} and names {@code velocity-api}.
     * Among resource files a game server comes before the proxy, so a jar that is both a Paper and a Velocity plugin
     * is Paper (a proxy workspace is {@code server_setup platform=velocity}).
     */
    private static final List<Sign> SIGNS = List.of(
            Sign.resource("quilt.mod.json", QUILT),
            Sign.resource("fabric.mod.json", FABRIC),
            Sign.resource("META-INF/neoforge.mods.toml", NEOFORGE),
            Sign.resource("META-INF/mods.toml", FORGE),
            Sign.resource("paper-plugin.yml", PAPER),
            Sign.resource("plugin.yml", PAPER),
            Sign.resource("velocity-plugin.json", VELOCITY),
            Sign.build("velocity-api", VELOCITY),
            Sign.build("minestom", MINESTOM));

    /** Platform of the Gradle project in {@code project}, or null when it does not say. */
    public static Platform detect(Path project) {
        for (Sign sign : SIGNS) {
            if (sign.shows(project)) {
                return sign.platform();
            }
        }
        return null;
    }

    /** True when one of the project's build files names {@code word}, ignoring case. */
    private static boolean mentions(Path project, String word) {
        for (String build : List.of("build.gradle.kts", "build.gradle", "gradle/libs.versions.toml")) {
            Path file = project.resolve(build);
            try {
                if (Files.isRegularFile(file) && Files.readString(file).toLowerCase(Locale.ROOT).contains(word)) {
                    return true;
                }
            } catch (IOException e) {
                // Not readable as text: no opinion.
            }
        }
        return false;
    }

    /** The command that starts this platform's server from its directory. */
    public List<String> launchCommand(String java) {
        return switch (this) {
            case PAPER, PURPUR, FOLIA -> List.of(java, "-Xmx2G", "-jar", serverJar, "--nogui");
            case FABRIC, QUILT, NEOFORGE, FORGE -> List.of(java, "-Xmx2G", "-jar", serverJar, "nogui");
            case MINESTOM -> List.of(java, "-Xmx1G", "-jar", serverJar);
            case VELOCITY -> List.of(java, "-Xmx512M", "-jar", serverJar);
        };
    }
}
