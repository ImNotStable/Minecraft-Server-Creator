package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.ToolException;
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
    PAPER("Paper", "paper.jar", "plugins", true, "paper"),
    /** Purpur, a Paper fork: Paper plugins and the Paper agent run on it unchanged. See ADR 0027. */
    PURPUR("Purpur", "purpur.jar", "plugins", true, "paper"),
    /** Fabric server launcher, mods in {@code mods/}. */
    FABRIC("Fabric", "fabric-server-launch.jar", "mods", true, "fabric"),
    /** Quilt server launcher; Quilt loads Fabric mods, so the Fabric agent and Fabric API run on it. See ADR 0027. */
    QUILT("Quilt", "quilt-server-launch.jar", "mods", true, "fabric"),
    /** NeoForge's server starter jar (it reads the installer's argument files), mods in {@code mods/}. */
    NEOFORGE("NeoForge", "server.jar", "mods", true, "neoforge"),
    /** Minecraft Forge's runnable shim jar, copied to a fixed name, mods in {@code mods/}. */
    FORGE("Forge", "forge-server.jar", "mods", true, "forge"),
    /** The project's own runnable jar is the server; the agent is built into it. No Mojang EULA step. */
    MINESTOM("Minestom", "server.jar", null, false, "minestom");

    private final String displayName;
    private final String serverJar;
    private final String addonDir;
    private final boolean eula;
    private final String agent;

    Platform(String displayName, String serverJar, String addonDir, boolean eula, String agent) {
        this.displayName = displayName;
        this.serverJar = serverJar;
        this.addonDir = addonDir;
        this.eula = eula;
        this.agent = agent;
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
     * {@code fabric}, {@code neoforge}, {@code forge} or {@code minestom}. Purpur shares Paper's, Quilt Fabric's.
     */
    public String agent() {
        return agent;
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
        Files.createDirectories(config.dataDir());
        Files.writeString(file(config), platform.id() + "\n");
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

    /** Platform of the Gradle project in {@code project}, or null when it does not say. */
    public static Platform detect(Path project) {
        Path resources = project.resolve("src/main/resources");
        if (Files.isRegularFile(resources.resolve("quilt.mod.json"))) {
            return QUILT;
        }
        if (Files.isRegularFile(resources.resolve("fabric.mod.json"))) {
            return FABRIC;
        }
        if (Files.isRegularFile(resources.resolve("META-INF/neoforge.mods.toml"))) {
            return NEOFORGE;
        }
        if (Files.isRegularFile(resources.resolve("META-INF/mods.toml"))) {
            return FORGE;
        }
        if (Files.isRegularFile(project.resolve("src/main/resources/paper-plugin.yml"))
                || Files.isRegularFile(project.resolve("src/main/resources/plugin.yml"))) {
            return PAPER;
        }
        for (String build : List.of("build.gradle.kts", "build.gradle", "gradle/libs.versions.toml")) {
            Path file = project.resolve(build);
            try {
                if (Files.isRegularFile(file) && Files.readString(file).toLowerCase(Locale.ROOT).contains("minestom")) {
                    return MINESTOM;
                }
            } catch (IOException e) {
                // Not readable as text: no opinion.
            }
        }
        return null;
    }

    /** The command that starts this platform's server from its directory. */
    public List<String> launchCommand(String java) {
        return switch (this) {
            case PAPER, PURPUR -> List.of(java, "-Xmx2G", "-jar", serverJar, "--nogui");
            case FABRIC, QUILT, NEOFORGE, FORGE -> List.of(java, "-Xmx2G", "-jar", serverJar, "nogui");
            case MINESTOM -> List.of(java, "-Xmx1G", "-jar", serverJar);
        };
    }
}
