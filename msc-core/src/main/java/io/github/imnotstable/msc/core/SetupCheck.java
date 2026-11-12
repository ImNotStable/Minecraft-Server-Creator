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
                    + "server project. Call msc_workspace with the server's folder, or start Claude Code there.");
        }
        if (config.installDir() != null) {
            String newer = newerInstall(config.installDir());
            String stale = newer != null ? newer : staleInstall(config.installDir());
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
                + seconds(newestTime) + ". Tools may be missing or behave differently. Run `"
                + LegacyInstall.installCommand(installDir, Path.of(source), home()) + "`, then reconnect msc in /mcp.";
    }

    /**
     * A warning when another install next to this one was installed later, or null. The msc plugin's launcher starts
     * the newest install (ADR 0021), so a reconnect picks it up.
     */
    static String newerInstall(Path installDir) {
        long own = installed(installDir);
        Path parent = installDir.toAbsolutePath().normalize().getParent();
        if (own < 0 || parent == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(parent);
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().startsWith("."))
                    .sorted().forEach(candidates::add);
        } catch (IOException e) {
            return null;
        }
        Path newest = null;
        long newestTime = own;
        for (Path d : candidates) {
            long t = installed(d);
            if (t > newestTime && !d.equals(installDir.toAbsolutePath().normalize()) && holdsCore(d)) {
                newest = d;
                newestTime = t;
            }
        }
        return newest == null ? null : "warning: a newer msc-core was installed " + seconds(newestTime) + " in "
                + newest + ". Reconnect msc in /mcp to use it.";
    }

    /** {@code installed} from an install's properties, or -1. */
    private static long installed(Path dir) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(dir.resolve("install.properties"))) {
            p.load(in);
            return Long.parseLong(p.getProperty("installed", "").trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static boolean holdsCore(Path dir) {
        try (Stream<Path> jars = Files.list(dir.resolve("lib"))) {
            return jars.anyMatch(j -> j.getFileName().toString().matches("msc-core-.*\\.jar"));
        } catch (IOException e) {
            return false;
        }
    }

    private static Path home() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home);
    }

    private static Instant seconds(long millis) {
        return Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS);
    }
}
