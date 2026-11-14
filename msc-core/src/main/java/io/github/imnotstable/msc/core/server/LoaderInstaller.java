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
import java.time.Duration;
import java.util.List;

/**
 * Sets up the servers that come from an installer (NeoForge, Forge, Quilt) or a fork's own download service
 * (Purpur). Installers and jars are cached in {@code .msc/cache}; an installer only runs when the server directory
 * does not have that loader version yet. Each step creates the server directory itself (#367). See ADR 0027.
 */
public final class LoaderInstaller {
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    /** Says which loader and versions the server directory holds, so another version is installed again. */
    static final String MARKER = ".msc-loader";

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
    private final Downloads downloads = new Downloads();

    public LoaderInstaller(Urls urls, Path cacheDir, String java) {
        this.urls = urls;
        this.cacheDir = cacheDir;
        this.java = java;
    }

    /** The newest Purpur build for a Minecraft version, as {@code purpur.jar}. */
    public Result purpur(String minecraft, Path dir) throws IOException, InterruptedException {
        URI latest = URI.create(urls.purpurApi() + "/v2/purpur/" + minecraft + "/latest");
        String build;
        try {
            HttpResponse<String> response = downloads.text(latest);
            if (response.statusCode() == 404) {
                throw new ToolException("Purpur has no version " + minecraft);
            }
            if (response.statusCode() != 200) {
                throw new ToolException("Purpur answered " + response.statusCode() + " for " + latest);
            }
            build = latestBuild(response.body());
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
            fetch(URI.create(urls.purpurApi() + "/v2/purpur/" + minecraft + "/" + build + "/download"), jar,
                    "Purpur has no build " + build + " of " + minecraft);
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

    /** NeoForge's server, with its server starter jar as {@code server.jar}. */
    public Result neoforge(String version, Path dir) throws IOException, InterruptedException {
        String id = "neoforge " + version;
        if (marked(dir, id) && Files.isDirectory(dir.resolve("libraries/net/neoforged/neoforge/" + version))
                && Files.exists(dir.resolve(Platform.NEOFORGE.serverJar()))) {
            return new Result(version, true);
        }
        Path installer = installer("neoforge-" + version + "-installer.jar",
                urls.neoforgeMaven() + "/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar",
                "NeoForge has no version " + version);
        String output = install(dir, List.of(java, "-jar", installer.toString(), "--install-server", dir.toString(),
                "--server-jar"), "NeoForge");
        if (!Files.exists(dir.resolve(Platform.NEOFORGE.serverJar()))) {
            throw wroteNothing("NeoForge", Platform.NEOFORGE.serverJar(), output);
        }
        mark(dir, id);
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
        if (marked(dir, id) && Files.isDirectory(dir.resolve("libraries/org/quiltmc/quilt-loader/" + loader))
                && Files.exists(dir.resolve(Platform.QUILT.serverJar()))) {
            return new Result(loader, true);
        }
        Path installer = installer("quilt-installer-" + installerVersion + ".jar",
                urls.quiltMaven() + "/org/quiltmc/quilt-installer/" + installerVersion + "/quilt-installer-"
                        + installerVersion + ".jar",
                "Quilt has no installer " + installerVersion);
        String output = install(dir, List.of(java, "-jar", installer.toString(), "install", "server", minecraft,
                loader,
                // Relative: the installer splits its options on spaces, so a folder like "MSC Quilt Test" broke it.
                "--download-server", "--install-dir=."), "Quilt");
        if (!Files.exists(dir.resolve(Platform.QUILT.serverJar()))) {
            throw wroteNothing("Quilt", Platform.QUILT.serverJar(), output);
        }
        mark(dir, id);
        return new Result(loader, false);
    }

    private static boolean marked(Path dir, String id) throws IOException {
        Path marker = dir.resolve(MARKER);
        return Files.exists(marker) && Files.readString(marker).strip().equals(id);
    }

    /** Whole or not at all ({@link AtomicFile}), so a marker is never a cut-off id. */
    static void mark(Path dir, String id) throws IOException {
        AtomicFile.write(dir.resolve(MARKER), to -> Files.writeString(to, id + "\n"));
    }

    private Path installer(String name, String url, String notFound) throws IOException, InterruptedException {
        Path jar = cacheDir.resolve(name);
        if (!Files.exists(jar)) {
            fetch(URI.create(url), jar, notFound);
        }
        return jar;
    }

    /** Runs an installer and returns its output; a failed or timed-out run throws with the output's tail. */
    private String install(Path dir, List<String> command, String what) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        // An install that fails half way must not leave the old marker saying the old version is complete.
        Files.deleteIfExists(dir.resolve(MARKER));
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
