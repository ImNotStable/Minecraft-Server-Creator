package io.github.imnotstable.msc.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts the newest msc-core installed under {@code ~/.msc/core} (ADR 0021). The Claude Code plugin runs it as
 * {@code java MscLauncher.java}, so it is one source file with no dependencies, and it stays on the Java 17 API: the
 * {@code java} on PATH may be older than the Java 25 the core needs.
 *
 * <p>An install is a folder holding {@code lib/msc-core-*.jar}: {@code installMsc} writes one per run
 * ({@code ~/.msc/core/msc-core-<version>-<time>}), and a release zip unzipped into {@code ~/.msc/core} makes
 * {@code msc-core-<version>}. The one installed last wins, so a new install is used at the next start (or
 * {@code /mcp} reconnect) with no {@code claude mcp add}. The workspace is the folder Claude Code runs the server in.
 */
public final class MscLauncher {
    /** Folder that holds the installs; default {@code ~/.msc/core}. */
    public static final String ENV_HOME = "MSC_CORE_HOME";
    /** A java executable to run the core with, when the one the install names or this one is too old. */
    public static final String ENV_JAVA = "MSC_CORE_JAVA";
    public static final String MAIN_CLASS = "io.github.imnotstable.msc.core.Main";
    public static final int REQUIRED_JAVA = 25;
    /** Written by {@code installMsc}; the launcher adds {@code installed} to installs that lack it. */
    public static final String INSTALL_PROPERTIES = "install.properties";

    private static final Pattern CORE_JAR = Pattern.compile("msc-core-(.+)\\.jar");
    private static final Pattern JAVA_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"(?:1\\.)?(\\d+)");

    private MscLauncher() {
    }

    /** One install: its folder, its version (from the core jar's name) and when it was installed. */
    public record Install(Path dir, String version, long installed) {
    }

    /** How to run the core: in this JVM (java is null) or with that java executable. */
    public record Plan(Install install, String java) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Plan plan;
        try {
            plan = plan(home(env, System.getProperty("user.home")), env, Path.of(System.getProperty("java.home")),
                    Runtime.version().feature(), System.currentTimeMillis());
        } catch (LaunchException e) {
            // Claude Code shows the server's stderr in /mcp when it fails to start.
            System.err.println("msc: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (plan.java() == null) {
            runHere(plan.install(), args);
        } else {
            System.exit(runChild(plan, args));
        }
    }

    /** The folder that holds the installs. */
    public static Path home(Map<String, String> env, String userHome) {
        String set = env.get(ENV_HOME);
        return set != null && !set.isBlank() ? Path.of(set) : Path.of(userHome, ".msc", "core");
    }

    /**
     * Picks the install and the Java to run it with.
     *
     * @param thisJava    {@code java.home} of the running JVM
     * @param thisFeature its feature version
     * @param now         stamped on installs that do not say when they were installed
     */
    public static Plan plan(Path home, Map<String, String> env, Path thisJava, int thisFeature, long now)
            throws LaunchException {
        Install install = newest(home, now).orElseThrow(() -> new LaunchException("no msc-core install in " + home
                + ". Install one: run `./gradlew :msc-core:installMsc` in the Minecraft Server Creator checkout, or "
                + "unzip a release's msc-core-<version>.zip into that folder. Then reconnect msc in /mcp."));
        return new Plan(install, java(install, env, thisJava, thisFeature));
    }

    /** The install folder with the latest install time, if any. */
    public static Optional<Install> newest(Path home, long now) {
        List<Install> found = new ArrayList<>();
        install(home, now).ifPresent(found::add);
        if (Files.isDirectory(home)) {
            try (Stream<Path> children = Files.list(home)) {
                // installMsc renames a folder to .delete-* before deleting it.
                children.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().startsWith("."))
                        .sorted().forEach(d -> install(d, now).ifPresent(found::add));
            } catch (IOException e) {
                // Use what was found.
            }
        }
        return found.stream().max(Comparator.comparingLong(Install::installed).thenComparing(i -> i.dir().toString()));
    }

    /** The install in this folder, if it holds one. */
    static Optional<Install> install(Path dir, long now) {
        Path lib = dir.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return Optional.empty();
        }
        String version = null;
        try (Stream<Path> jars = Files.list(lib)) {
            for (Path jar : jars.toList()) {
                Matcher m = CORE_JAR.matcher(jar.getFileName().toString());
                if (m.matches()) {
                    version = m.group(1);
                    break;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(new Install(dir, version, installed(dir, now)));
    }

    /**
     * When the install was made: {@code installed} in its properties file. An unzipped release has none, so the
     * first launcher to see it records {@code now}: it was installed after every install already seen. A folder that
     * cannot be written falls back to its {@code lib} folder's time.
     */
    static long installed(Path dir, long now) {
        Path file = dir.resolve(INSTALL_PROPERTIES);
        Properties p = load(file);
        String value = p.getProperty("installed");
        if (value != null) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                // Rewrite it below.
            }
        }
        p.setProperty("installed", Long.toString(now));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "installed: first seen by the msc launcher");
            return now;
        } catch (IOException e) {
            try {
                return Files.getLastModifiedTime(dir.resolve("lib")).toMillis();
            } catch (IOException e2) {
                return 0;
            }
        }
    }

    /**
     * The java to start the core with, or null to run it in this JVM: {@code MSC_CORE_JAVA}, then the java the install
     * was built with ({@code java} in its properties), then this JVM, then {@code JAVA_HOME}. Each must be Java 25+.
     */
    static String java(Install install, Map<String, String> env, Path thisJava, int thisFeature)
            throws LaunchException {
        String forced = env.get(ENV_JAVA);
        if (forced != null && !forced.isBlank()) {
            return forced;
        }
        String recorded = load(install.dir().resolve(INSTALL_PROPERTIES)).getProperty("java");
        if (recorded != null && Files.isRegularFile(Path.of(recorded))) {
            Path home = Path.of(recorded).getParent();
            home = home == null ? null : home.getParent();
            if (home != null && home.normalize().equals(thisJava.normalize()) && thisFeature >= REQUIRED_JAVA) {
                return null;
            }
            if (home != null && feature(home) >= REQUIRED_JAVA) {
                return recorded;
            }
        }
        if (thisFeature >= REQUIRED_JAVA) {
            return null;
        }
        String javaHome = env.get("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank() && feature(Path.of(javaHome)) >= REQUIRED_JAVA) {
            return executable(Path.of(javaHome)).toString();
        }
        throw new LaunchException("msc-core needs Java " + REQUIRED_JAVA + " or newer; this is Java " + thisFeature
                + " and JAVA_HOME is " + (javaHome == null ? "not set" : javaHome) + ". Set " + ENV_JAVA
                + " to a Java " + REQUIRED_JAVA + " java executable, or put one first on PATH.");
    }

    /** A JDK's feature version from its {@code release} file, or 0. */
    static int feature(Path javaHome) {
        try {
            Matcher m = JAVA_VERSION.matcher(Files.readString(javaHome.resolve("release"), StandardCharsets.UTF_8));
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static Path executable(Path javaHome) {
        boolean windows = File.separatorChar == '\\';
        return javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");
    }

    /** The class path of an install: every jar in {@code lib}. */
    static List<Path> classPath(Install install) throws IOException {
        try (Stream<Path> jars = Files.list(install.dir().resolve("lib"))) {
            return jars.filter(j -> j.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }

    /** The child command for {@link Plan#java()}. */
    public static List<String> command(Plan plan, String[] args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(plan.java());
        cmd.add("-cp");
        cmd.add(plan.install().dir().resolve("lib") + File.separator + "*");
        cmd.add(MAIN_CLASS);
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static void runHere(Install install, String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Path jar : classPath(install)) {
            urls.add(jar.toUri().toURL());
            paths.add(jar.toString());
        }
        // The core starts child JVMs on its own class path (the standalone pack host of server_detach); a source
        // launch leaves java.class.path at ".", so name the core's jars as a -cp launch would.
        System.setProperty("java.class.path", String.join(File.pathSeparator, paths));
        // Not closed: the core runs until it calls System.exit.
        ClassLoader loader = new URLClassLoader("msc-core", urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Method main = Class.forName(MAIN_CLASS, true, loader).getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    /**
     * Runs the core as a child sharing this process's stdin and stdout, so MCP frames pass straight through. The
     * core exits when Claude Code closes stdin; the shutdown hook covers a launcher that is stopped first.
     */
    private static int runChild(Plan plan, String[] args) throws IOException, InterruptedException {
        Process child = new ProcessBuilder(command(plan, args)).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy, "msc-launcher-stop"));
        return child.waitFor();
    }

    private static Properties load(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            // None yet.
        }
        return p;
    }

    /** Why the core cannot start, in one line for {@code /mcp}. */
    public static final class LaunchException extends Exception {
        private static final long serialVersionUID = 1L;

        public LaunchException(String message) {
            super(message);
        }
    }
}
