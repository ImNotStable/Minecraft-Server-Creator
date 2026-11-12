package io.github.imnotstable.msc.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Setup mistakes that otherwise only show as confusing tool results: a session started in the MSC repo instead of
 * the server's folder, and an install older than the checkout it was built from.
 */
public final class SetupCheck {
    private SetupCheck() {
    }

    public static List<String> warnings(CoreConfig config) {
        List<String> out = new ArrayList<>();
        if (isMscRepo(config.workspace())) {
            out.add("warning: the workspace is the Minecraft Server Creator repo (" + config.workspace() + "), not a "
                    + "server project. Start Claude Code in the server's folder, or register MSC for this folder with "
                    + "`claude mcp add msc --scope local -e MSC_WORKSPACE=<server folder> -- <install command>`.");
        }
        if (config.installDir() != null) {
            String stale = staleInstall(config.installDir());
            if (stale != null) {
                out.add(stale);
            }
        }
        return out;
    }

    /** The repo's own root: its settings file names the root project. */
    static boolean isMscRepo(Path dir) {
        Path settings = dir.resolve("settings.gradle.kts");
        try {
            return Files.isRegularFile(settings) && Files.isDirectory(dir.resolve("msc-core"))
                    && Files.readString(settings).contains("rootProject.name = \"minecraft-server-creator\"");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A warning when a main source file in the checkout {@code installMsc} ran from is newer than the install, or
     * null. Installs from a release zip have no {@code install.properties} and are not checked.
     */
    static String staleInstall(Path installDir) {
        Path file = installDir.resolve("install.properties");
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String source = p.getProperty("source");
        long installed;
        try {
            installed = Long.parseLong(p.getProperty("installed", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (source == null || !isMscRepo(Path.of(source))) {
            return null;
        }
        Path newest = null;
        long newestTime = installed;
        try (Stream<Path> modules = Files.list(Path.of(source))) {
            for (Path module : modules.filter(m -> m.getFileName().toString().startsWith("msc-")).toList()) {
                Path main = module.resolve("src/main");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(main)) {
                    for (Path f : walk.filter(Files::isRegularFile).toList()) {
                        long t = Files.getLastModifiedTime(f).toMillis();
                        if (t > newestTime) {
                            newestTime = t;
                            newest = f;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (newest == null) {
            return null;
        }
        return "warning: this msc-core (installed " + seconds(installed) + " in " + installDir + ") is older than its "
                + "checkout: " + Path.of(source).relativize(newest).toString().replace('\\', '/') + " changed "
                + seconds(newestTime) + ". Tools may be missing or behave differently. Run `./gradlew "
                + ":msc-core:installMsc` in " + source + ", then restart Claude Code.";
    }

    private static Instant seconds(long millis) {
        return Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS);
    }
}
