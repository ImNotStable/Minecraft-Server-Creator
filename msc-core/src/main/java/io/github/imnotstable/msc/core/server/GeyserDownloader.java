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
 * and kept in the machine's {@link DownloadStore} (ADR 0046). The newest build wins; while the API is unavailable,
 * the newest cached one ({@link Downloads#orCached}).
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

    /**
     * Which build of the two plugins a platform loads (ADR 0088): the Spigot ones on Paper and Purpur, the Velocity and
     * BungeeCord ones on those proxies, where Geyser fronts the proxy's own port. Each has its own download key, jar
     * names and Geyser config folder.
     */
    public enum Flavor {
        SPIGOT("spigot", "spigot", "Geyser-Spigot", "floodgate-spigot.jar"),
        VELOCITY("velocity", "velocity", "Geyser-Velocity", "floodgate-velocity.jar"),
        BUNGEE("bungeecord", "bungee", "Geyser-BungeeCord", "floodgate-bungee.jar");

        private final String geyserKey;
        private final String floodgateKey;
        private final String geyser;
        private final String floodgateJar;

        Flavor(String geyserKey, String floodgateKey, String geyser, String floodgateJar) {
            this.geyserKey = geyserKey;
            this.floodgateKey = floodgateKey;
            this.geyser = geyser;
            this.floodgateJar = floodgateJar;
        }

        public static Flavor of(Platform platform) {
            return switch (platform) {
                case VELOCITY -> VELOCITY;
                case WATERFALL -> BUNGEE;
                default -> SPIGOT;
            };
        }

        /** The API's download key of {@code project} in this flavor. */
        public String key(Project project) {
            return project == Project.GEYSER ? geyserKey : floodgateKey;
        }

        /** The API's file name of {@code project} in this flavor, kept in {@code plugins/}. */
        public String jarName(Project project) {
            return project == Project.GEYSER ? geyser + ".jar" : floodgateJar;
        }

        /** Geyser's config folder in {@code plugins/}. */
        public String folder() {
            return geyser;
        }

        /** The flavor whose Geyser jar is in {@code plugins}, or null. */
        public static Flavor installed(Path plugins) {
            for (Flavor f : values()) {
                if (Files.isRegularFile(plugins.resolve(f.jarName(Project.GEYSER)))) {
                    return f;
                }
            }
            return null;
        }
    }

    /**
     * A downloaded jar; {@code version} is null for a cached fallback, whose name keeps only the build, and
     * {@code note} then says in one line why the API was not used (#589).
     */
    public record Download(Project project, Flavor flavor, Path jar, String version, int build, boolean cached,
                           String note) {
        /** {@code geyser 2.12.1 build 1302}, {@code geyser build 1302} offline, {@code geyser} for a kept jar. */
        public String describe() {
            return project.id() + (version == null ? "" : " " + version) + (build > 0 ? " build " + build : "");
        }
    }

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
        return download(project, Flavor.SPIGOT, null);
    }

    /**
     * {@link #download(Project)}, falling back last to {@code installed}, the jar already in {@code plugins/} (its build
     * unknown, so 0), when the API is unavailable and nothing is cached: a folder that has Geyser keeps it.
     */
    public Download download(Project project, Path installed) throws IOException, InterruptedException {
        return download(project, Flavor.SPIGOT, installed);
    }

    /** As {@link #download(Project, Path)}, for the plugins of {@code flavor}. */
    public Download download(Project project, Flavor flavor, Path installed) throws IOException, InterruptedException {
        URI latest = URI.create(baseUrl + "/v2/projects/" + project.id() + "/versions/latest/builds/latest");
        return Downloads.orCached(() -> newest(project, flavor, latest), note -> {
            CachedBuild offline = CachedBuild.newest(cacheDir, project.id(), flavor.key(project));
            if (offline != null) {
                return new Download(project, flavor, offline.jar(), null, offline.build(), true, note);
            }
            return installed != null && Files.isRegularFile(installed) ? new Download(project, flavor, installed, null,
                    0, true,
                    note.substring(0, note.length() - Downloads.CACHED.length()) + "; keeping the jar in plugins/")
                    : null;
        });
    }

    private Download newest(Project project, Flavor flavor, URI latest) throws IOException, InterruptedException {
        HttpResponse<String> response = downloads.text(latest, "GeyserMC downloads");
        if (response.statusCode() != 200) {
            throw new ToolException("GeyserMC downloads answered " + response.statusCode() + " for " + latest);
        }
        String key = flavor.key(project);
        Build b = parse(project, key, response.body());
        Path jar = cacheDir.resolve(project.id() + "-" + key + "-" + b.build() + ".jar");
        if (Files.exists(jar) && b.sha256().equalsIgnoreCase(Downloads.sha256(jar))) {
            return new Download(project, flavor, jar, b.version(), b.build(), true, null);
        }
        URI file = URI.create(baseUrl + "/v2/projects/" + project.id() + "/versions/" + b.version() + "/builds/"
                + b.build() + "/downloads/" + key);
        Files.createDirectories(cacheDir);
        boolean stored = downloads.fetch(file, jar, "download of " + file + " answered 404",
                Downloads.Checksum.of("SHA-256", b.sha256()));
        return new Download(project, flavor, jar, b.version(), b.build(), stored, null);
    }

    /** The build an API answer names: version and build as they go into the download URL, and the jar's sha256. */
    record Build(String version, int build, String sha256) {
    }

    /** Reads a {@code builds/latest} answer; refuses one without a usable version, build or Spigot sha256. */
    static Build parse(Project project, String json) {
        return parse(project, Flavor.SPIGOT.key(project), json);
    }

    /** As {@link #parse(Project, String)}, for the jar under download key {@code key}. */
    static Build parse(Project project, String key, String json) {
        String what = "GeyserMC's answer for " + project.id();
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String version = string(o.get("version"));
            JsonElement build = o.get("build");
            JsonObject downloads = o.has("downloads") && o.get("downloads").isJsonObject()
                    ? o.getAsJsonObject("downloads") : new JsonObject();
            JsonElement spigot = downloads.get(key);
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
                throw new ToolException(what + " names no sha256 for the " + key + " jar");
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
