package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Sets up the servers that come from an installer (NeoForge, Forge, Quilt) or a fork's own download service
 * (Purpur). Installers and jars are cached in {@code .msc/cache}; an installer only runs when the server directory
 * does not have that loader version yet. Each step creates the server directory itself (#367). See ADR 0027.
 */
public final class LoaderInstaller {
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    /**
     * Says which loader and versions the server directory holds, so another version is installed again, and the
     * SHA-256 of each file that loader's start reads, so a file another platform wrote since under the same name
     * (NeoForge, Minestom and the vanilla jar of Fabric and Quilt all use {@code server.jar}) is not taken for its own
     * (#513). First line the id, then one {@code <sha256> <file>} line per file.
     */
    static final String MARKER = ".msc-loader";
    /** The one start file name several platforms share: see {@link #claimSharedJar}. */
    static final String SHARED_JAR = "server.jar";

    /** Where each project publishes. Tests point these at a local server. */
    public record Urls(String neoforgeMaven, String forgeMaven, String quiltMaven, String purpurApi) {
        public static final Urls DEFAULT = new Urls("https://maven.neoforged.net/releases",
                "https://maven.minecraftforge.net", "https://maven.quiltmc.org/repository/release",
                "https://api.purpurmc.org");

        public Urls {
            neoforgeMaven = Downloads.trim(neoforgeMaven);
            forgeMaven = Downloads.trim(forgeMaven);
            quiltMaven = Downloads.trim(quiltMaven);
            purpurApi = Downloads.trim(purpurApi);
        }
    }

    /** What a step did, in one line for the tool result. */
    public record Result(String version, boolean cached) {
    }

    private final Urls urls;
    private final Path cacheDir;
    private final String java;
    private final Downloads downloads;

    /** Without the machine's store, for tests. */
    public LoaderInstaller(Urls urls, Path cacheDir, String java) {
        this(urls, cacheDir, java, DownloadStore.NONE);
    }

    public LoaderInstaller(Urls urls, Path cacheDir, String java, DownloadStore store) {
        this.urls = urls;
        this.cacheDir = cacheDir;
        this.java = java;
        this.downloads = new Downloads(store);
    }

    /** The newest Purpur build for a Minecraft version, as {@code purpur.jar}. */
    public Result purpur(String minecraft, Path dir) throws IOException, InterruptedException {
        URI latest = URI.create(urls.purpurApi() + "/v2/purpur/" + minecraft + "/latest");
        String build;
        String md5;
        try {
            HttpResponse<String> response = downloads.text(latest);
            if (response.statusCode() == 404) {
                throw new ToolException("Purpur has no version " + minecraft);
            }
            if (response.statusCode() != 200) {
                throw new ToolException("Purpur answered " + response.statusCode() + " for " + latest);
            }
            build = latestBuild(response.body());
            md5 = md5(response.body());
        } catch (IOException e) {
            CachedBuild offline = CachedBuild.newest(cacheDir, "purpur", minecraft);
            if (offline == null) {
                throw new ToolException("cannot reach " + latest + ": " + e.getMessage());
            }
            ServerFiles.place(offline.jar(), dir, Platform.PURPUR.serverJar());
            return new Result(minecraft + " build " + offline.build(), true);
        }
        Path jar = cacheDir.resolve("purpur-" + minecraft + "-" + build + ".jar");
        boolean cached = Files.exists(jar);
        if (!cached) {
            cached = downloads.fetch(URI.create(urls.purpurApi() + "/v2/purpur/" + minecraft + "/" + build + "/download"), jar,
                    "Purpur has no build " + build + " of " + minecraft, Downloads.Checksum.of("MD5", md5));
        }
        ServerFiles.place(jar, dir, Platform.PURPUR.serverJar());
        return new Result(minecraft + " build " + build, cached);
    }

    static String latestBuild(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (!o.has("build")) {
            throw new ToolException("Purpur's answer names no build");
        }
        return o.get("build").getAsString();
    }

    /** The MD5 of the build's jar that Purpur's answer names, or null when it names none. */
    static String md5(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return o.has("md5") && o.get("md5").isJsonPrimitive() ? o.get("md5").getAsString() : null;
    }

    /** NeoForge's server, with its server starter jar as {@code server.jar}. */
    public Result neoforge(String version, Path dir) throws IOException, InterruptedException {
        String id = "neoforge " + version;
        if (marked(dir, id, Platform.NEOFORGE.serverJar())
                && Files.isDirectory(dir.resolve("libraries/net/neoforged/neoforge/" + version))) {
            return new Result(version, true);
        }
        Path installer = installer("neoforge-" + version + "-installer.jar",
                urls.neoforgeMaven() + "/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar",
                "NeoForge has no version " + version);
        String output = install(dir, List.of(java, "-jar", installer.toString(), "--install-server", dir.toString(),
                "--server-jar"), "NeoForge", Platform.NEOFORGE.serverJar());
        if (!Files.exists(dir.resolve(Platform.NEOFORGE.serverJar()))) {
            throw wroteNothing("NeoForge", Platform.NEOFORGE.serverJar(), output);
        }
        mark(dir, id, Platform.NEOFORGE.serverJar());
        return new Result(version, false);
    }

    /** Minecraft Forge's server; its runnable shim jar is copied to {@code forge-server.jar}. */
    public Result forge(String version, Path dir) throws IOException, InterruptedException {
        Path shim = dir.resolve("forge-" + version + "-shim.jar");
        if (!Files.exists(shim)) {
            Path installer = installer("forge-" + version + "-installer.jar",
                    urls.forgeMaven() + "/net/minecraftforge/forge/" + version + "/forge-" + version + "-installer.jar",
                    "Forge has no version " + version);
            String output = install(dir, List.of(java, "-jar", installer.toString(), "--installServer",
                    dir.toString()), "Forge");
            if (!Files.exists(shim)) {
                throw wroteNothing("Forge", shim.getFileName().toString(), output);
            }
        } else {
            // The fixed-name jar may be another version's shim: it is this one only when the bytes match.
            Path fixed = dir.resolve(Platform.FORGE.serverJar());
            if (Files.exists(fixed) && Files.mismatch(shim, fixed) == -1) {
                return new Result(version, true);
            }
        }
        ServerFiles.place(shim, dir, Platform.FORGE.serverJar());
        return new Result(version, false);
    }

    /** Quilt's server launcher plus the vanilla server jar it starts. */
    public Result quilt(String minecraft, String loader, String installerVersion, Path dir)
            throws IOException, InterruptedException {
        String id = "quilt " + minecraft + " " + loader;
        // The launcher starts the vanilla jar the installer downloaded as server.jar.
        if (marked(dir, id, Platform.QUILT.serverJar(), SHARED_JAR)
                && Files.isDirectory(dir.resolve("libraries/org/quiltmc/quilt-loader/" + loader))) {
            return new Result(loader, true);
        }
        Path installer = installer("quilt-installer-" + installerVersion + ".jar",
                urls.quiltMaven() + "/org/quiltmc/quilt-installer/" + installerVersion + "/quilt-installer-"
                        + installerVersion + ".jar",
                "Quilt has no installer " + installerVersion);
        String output = install(dir, List.of(java, "-jar", installer.toString(), "install", "server", minecraft,
                loader,
                // Relative: the installer splits its options on spaces, so a folder like "MSC Quilt Test" broke it.
                "--download-server", "--install-dir=."), "Quilt", Platform.QUILT.serverJar(), SHARED_JAR);
        for (String jar : List.of(Platform.QUILT.serverJar(), SHARED_JAR)) {
            if (!Files.exists(dir.resolve(jar))) {
                throw wroteNothing("Quilt", jar, output);
            }
        }
        mark(dir, id, Platform.QUILT.serverJar(), SHARED_JAR);
        return new Result(loader, false);
    }

    /**
     * True when the marker names {@code id} and each of {@code files} is still the file that install recorded: same
     * name, same SHA-256. Existing is not enough, since another platform's setup or deploy may have written the name.
     */
    private static boolean marked(Path dir, String id, String... files) throws IOException {
        List<String> lines = markerLines(dir);
        if (lines.isEmpty() || !lines.getFirst().equals(id)) {
            return false;
        }
        for (String file : files) {
            Path path = dir.resolve(file);
            if (!Files.isRegularFile(path) || !lines.contains(sha256(path) + " " + file)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> markerLines(Path dir) throws IOException {
        Path marker = dir.resolve(MARKER);
        return Files.isRegularFile(marker) ? Files.readString(marker).strip().lines().map(String::strip).toList()
                : List.of();
    }

    /** Whole or not at all ({@link AtomicFile}), so a marker is never a cut-off id. Records each file's hash. */
    static void mark(Path dir, String id, String... files) throws IOException {
        StringBuilder text = new StringBuilder(id).append('\n');
        for (String file : files) {
            text.append(sha256(dir.resolve(file))).append(' ').append(file).append('\n');
        }
        AtomicFile.write(dir.resolve(MARKER), to -> Files.writeString(to, text));
    }

    /**
     * For a platform whose start reads {@code server.jar} without its setup installing it: the Fabric launcher
     * downloads the vanilla server there on first start, and Minestom's dev_cycle deploys the project jar there. The
     * one entry point both server_setup and the Minestom deploy go through, so the folder always says whose
     * {@code server.jar} it holds. The jar is kept only when the marker names {@code id} and records the jar's
     * SHA-256; anything else (no marker, another id, the old one-line marker, a changed jar) is unknown and removed
     * (#524), so the launcher downloads its own or server_start asks for a dev_cycle. Then the folder is marked as
     * {@code id}'s, vouching for the jar only when it was kept. Returns why a jar was removed, for
     * {@link #removedLine}, or null when none was.
     */
    public static String claimSharedJar(Path dir, String id) throws IOException {
        Path jar = dir.resolve(SHARED_JAR);
        if (Files.isRegularFile(jar) && marked(dir, id, SHARED_JAR)) {
            return null;
        }
        String reason = null;
        if (Files.exists(jar)) {
            List<String> lines = markerLines(dir);
            reason = lines.isEmpty() ? ": no marker says which platform it belongs to"
                    : !lines.getFirst().equals(id) ? ", which the folder held for " + lines.getFirst()
                    : ": the marker for " + id + " records no matching hash for it";
            Files.delete(jar);
        }
        mark(dir, id);
        return reason;
    }

    /** After a deploy copied {@code server.jar}: the folder is {@code id}'s and vouches for that very jar. */
    public static void markSharedJar(Path dir, String id) throws IOException {
        mark(dir, id, SHARED_JAR);
    }

    /** The line a tool reply gives a jar {@link #claimSharedJar} removed, or "" when it removed none. */
    public static String removedLine(String reason, String next) {
        return reason == null ? "" : "removed " + SHARED_JAR + reason + "; " + next + "\n";
    }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    private Path installer(String name, String url, String notFound) throws IOException, InterruptedException {
        Path jar = cacheDir.resolve(name);
        if (!Files.exists(jar)) {
            fetch(URI.create(url), jar, notFound);
        }
        return jar;
    }

    /** Runs an installer and returns its output; a failed or timed-out run throws with the output's tail. */
    private String install(Path dir, List<String> command, String what, String... starts)
            throws IOException, InterruptedException {
        Files.createDirectories(dir);
        // An install that fails half way must not leave the old marker saying the old version is complete.
        Files.deleteIfExists(dir.resolve(MARKER));
        // Nor an installer that keeps an existing file leave another platform's jar under its start name (#513).
        for (String start : starts) {
            Files.deleteIfExists(dir.resolve(start));
        }
        ProcessRunner.Result r = ProcessRunner.run(command, dir, INSTALL_TIMEOUT);
        if (!r.ok()) {
            throw new ToolException("the " + what + " installer " + (r.timedOut() ? "timed out" : "failed (exit "
                    + r.exitCode() + ")") + ":\n" + tail(r.output(), 15));
        }
        return r.output();
    }

    /** An installer that exits 0 without its jar says why in its output (a failed download, for example). */
    private static ToolException wroteNothing(String what, String jar, String output) {
        String tail = tail(output, 15);
        return new ToolException("the " + what + " installer finished but wrote no " + jar
                + (tail.isEmpty() ? "" : ":\n" + tail));
    }

    static String tail(String text, int lines) {
        List<String> all = text.lines().filter(l -> !l.isBlank()).toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    private void fetch(URI uri, Path jar, String notFound) throws IOException, InterruptedException {
        downloads.fetch(uri, jar, notFound, null);
    }
}
