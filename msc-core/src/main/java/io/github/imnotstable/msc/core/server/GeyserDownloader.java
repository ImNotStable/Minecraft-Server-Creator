package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Downloads the Spigot builds of Geyser and Floodgate (Paper and Purpur load them as plugins) from GeyserMC's download
 * API (v2) into {@code .msc/cache} as {@code <project>-spigot-<build>.jar}, checked against the sha256 the API names
 * and kept in the machine's {@link DownloadStore} (ADR 0046). The newest build wins; offline, the newest cached one.
 */
public final class GeyserDownloader {
    /** The two plugins, by their GeyserMC project id and the file name they get in {@code plugins/}. */
    public enum Project {
        GEYSER("geyser", "Geyser-Spigot.jar"),
        FLOODGATE("floodgate", "floodgate-spigot.jar");

        private final String id;
        private final String jarName;

        Project(String id, String jarName) {
            this.id = id;
            this.jarName = jarName;
        }

        public String id() {
            return id;
        }

        /** The name in {@code plugins/}: the API's own name, so a rerun replaces the same file. */
        public String jarName() {
            return jarName;
        }
    }

    /** A downloaded jar; {@code version} is null for an offline fallback, whose name keeps only the build. */
    public record Download(Project project, Path jar, String version, int build, boolean cached) {
        /** {@code geyser 2.12.1 build 1302}, or {@code geyser build 1302} offline. */
        public String describe() {
            return project.id() + (version == null ? "" : " " + version) + " build " + build;
        }
    }

    private static final String PLATFORM = "spigot";
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final String baseUrl;
    private final Path cacheDir;
    private final Downloads downloads;

    public GeyserDownloader(String baseUrl, Path cacheDir, DownloadStore store) {
        this.baseUrl = Downloads.trim(baseUrl);
        this.cacheDir = cacheDir;
        this.downloads = new Downloads(store);
    }

    public Download download(Project project) throws IOException, InterruptedException {
        URI latest = URI.create(baseUrl + "/v2/projects/" + project.id() + "/versions/latest/builds/latest");
        HttpResponse<String> response;
        try {
            response = downloads.text(latest);
        } catch (IOException e) {
            CachedBuild offline = CachedBuild.newest(cacheDir, project.id(), PLATFORM);
            if (offline != null) {
                return new Download(project, offline.jar(), null, offline.build(), true);
            }
            throw new ToolException("cannot reach " + latest + ": " + e.getMessage());
        }
        if (response.statusCode() != 200) {
            throw new ToolException("GeyserMC downloads answered " + response.statusCode() + " for " + latest);
        }
        Build b = parse(project, response.body());
        Path jar = cacheDir.resolve(project.id() + "-" + PLATFORM + "-" + b.build() + ".jar");
        if (Files.exists(jar) && b.sha256().equalsIgnoreCase(Downloads.sha256(jar))) {
            return new Download(project, jar, b.version(), b.build(), true);
        }
        URI file = URI.create(baseUrl + "/v2/projects/" + project.id() + "/versions/" + b.version() + "/builds/"
                + b.build() + "/downloads/" + PLATFORM);
        Files.createDirectories(cacheDir);
        boolean stored = downloads.fetch(file, jar, "download of " + file + " answered 404",
                Downloads.Checksum.of("SHA-256", b.sha256()));
        return new Download(project, jar, b.version(), b.build(), stored);
    }

    /** The build an API answer names: version and build as they go into the download URL, and the jar's sha256. */
    record Build(String version, int build, String sha256) {
    }

    /** Reads a {@code builds/latest} answer; refuses one without a usable version, build or Spigot sha256. */
    static Build parse(Project project, String json) {
        String what = "GeyserMC's answer for " + project.id();
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String version = string(o.get("version"));
            JsonElement build = o.get("build");
            JsonObject downloads = o.has("downloads") && o.get("downloads").isJsonObject()
                    ? o.getAsJsonObject("downloads") : new JsonObject();
            JsonElement spigot = downloads.get(PLATFORM);
            String sha = spigot != null && spigot.isJsonObject() ? string(spigot.getAsJsonObject().get("sha256")) : null;
            if (version == null || !VERSION.matcher(version).matches()) {
                throw new ToolException(what + " names no usable version");
            }
            if (build == null || !build.isJsonPrimitive() || !build.getAsJsonPrimitive().isNumber()
                    || build.getAsDouble() != Math.rint(build.getAsDouble()) || build.getAsDouble() < 0
                    || build.getAsDouble() > 999_999_999) {
                throw new ToolException(what + " names no usable build");
            }
            if (sha == null || !SHA256.matcher(sha).matches()) {
                throw new ToolException(what + " names no sha256 for the " + PLATFORM + " jar");
            }
            return new Build(version, build.getAsInt(), sha);
        } catch (JsonParseException | IllegalStateException e) {
            throw new ToolException(what + " is not the expected JSON");
        }
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
