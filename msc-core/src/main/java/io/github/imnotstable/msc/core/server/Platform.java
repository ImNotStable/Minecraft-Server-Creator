package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The server platform a workspace targets. It decides what {@code server_setup} installs, how the server
 * starts and where {@code dev_cycle} puts the built jar. See ADR 0011.
 */
public enum Platform {
    /** Paper jar, plugins in {@code plugins/}. */
    PAPER("paper.jar", "plugins", true),
    /** Fabric server launcher, mods in {@code mods/}. */
    FABRIC("fabric-server-launch.jar", "mods", true),
    /** The project's own runnable jar is the server; the agent is built into it. No Mojang EULA step. */
    MINESTOM("server.jar", null, false);

    private final String serverJar;
    private final String addonDir;
    private final boolean eula;

    Platform(String serverJar, String addonDir, boolean eula) {
        this.serverJar = serverJar;
        this.addonDir = addonDir;
        this.eula = eula;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** File name of the jar the server starts from, inside the server directory. */
    public String serverJar() {
        return serverJar;
    }

    /** Folder in the server directory that plugins or mods go in, or null when the project jar is the server. */
    public String addonDir() {
        return addonDir;
    }

    /** True when the server is Mojang's and so needs an accepted {@code eula.txt}. */
    public boolean needsEula() {
        return eula;
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
        if (Files.isRegularFile(project.resolve("src/main/resources/fabric.mod.json"))) {
            return FABRIC;
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
            case PAPER -> List.of(java, "-Xmx2G", "-jar", serverJar, "--nogui");
            case FABRIC -> List.of(java, "-Xmx2G", "-jar", serverJar, "nogui");
            case MINESTOM -> List.of(java, "-Xmx1G", "-jar", serverJar);
        };
    }
}
