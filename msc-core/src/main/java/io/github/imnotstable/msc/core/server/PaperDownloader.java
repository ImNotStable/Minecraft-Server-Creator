package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads a Paper, Folia or Velocity jar from PaperMC's downloads service (Fill, API v3) into a local cache.
 * The newest stable build of a version wins; without one, the newest build of any channel. While the service is
 * unavailable, the newest cached build ({@link Downloads#orCached}).
 */
public final class PaperDownloader {
    /**
     * A downloaded jar. {@code note} is null, or one line saying why the newest cached build is used: the service was
     * unavailable (#589).
     */
    public record Download(Path jar, String version, int build, boolean cached, String note) {
        public Download(Path jar, String version, int build, boolean cached) {
            this(jar, version, build, cached, null);
        }
    }

    private final String baseUrl;
    private final Path cacheDir;
    private final Downloads downloads;

    /** Without the machine's store, for tests. */
    public PaperDownloader(String baseUrl, Path cacheDir) {
        this(baseUrl, cacheDir, DownloadStore.NONE);
    }

    public PaperDownloader(String baseUrl, Path cacheDir, DownloadStore store) {
        this.baseUrl = Downloads.trim(baseUrl);
        this.cacheDir = cacheDir;
        this.downloads = new Downloads(store);
    }

    /** A Paper jar. */
    public Download download(String version) throws IOException, InterruptedException {
        return download(Platform.PAPER, version);
    }

    /**
     * A jar of {@code platform}'s Fill project ({@code paper}, {@code folia} or {@code velocity}: the platform's id).
     * {@code version} is the project's own version: Minecraft's for Paper and Folia, Velocity's for Velocity.
     */
    public Download download(Platform platform, String version) throws IOException, InterruptedException {
        String project = platform.id();
        URI builds = URI.create(baseUrl + "/v3/projects/" + project + "/versions/" + version + "/builds");
        return Downloads.orCached(() -> newest(platform, version, builds), note -> {
            CachedBuild offline = CachedBuild.newest(cacheDir, project, version);
            return offline == null ? null : new Download(offline.jar(), version, offline.build(), true, note);
        });
    }

    private Download newest(Platform platform, String version, URI builds) throws IOException, InterruptedException {
        String project = platform.id();
        String name = platform.displayName();
        HttpResponse<String> response = downloads.text(builds, name + " downloads");
        if (response.statusCode() == 404) {
            throw new ToolException(name + " has no version " + version);
        }
        if (response.statusCode() != 200) {
            throw new ToolException(name + " downloads answered " + response.statusCode() + " for " + builds);
        }
        JsonObject best = null;
        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement e : array) {
            JsonObject b = e.getAsJsonObject();
            if (best == null || rank(b) > rank(best)) {
                best = b;
            }
        }
        if (best == null) {
            throw new ToolException(name + " has no builds for " + version);
        }
        int build = best.get("id").getAsInt();
        JsonObject file = best.getAsJsonObject("downloads").getAsJsonObject("server:default");
        String url = file.get("url").getAsString();
        String sha256 = file.has("checksums") ? file.getAsJsonObject("checksums").get("sha256").getAsString() : null;
        Path jar = cacheDir.resolve(project + "-" + version + "-" + build + ".jar");
        if (Files.exists(jar) && (sha256 == null || sha256.equalsIgnoreCase(Downloads.sha256(jar)))) {
            return new Download(jar, version, build, true);
        }
        boolean stored = downloads.fetch(URI.create(url), jar, "download of " + url + " answered 404",
                Downloads.Checksum.of("SHA-256", sha256));
        return new Download(jar, version, build, stored);
    }

    private static long rank(JsonObject build) {
        boolean stable = build.has("channel") && "STABLE".equalsIgnoreCase(build.get("channel").getAsString());
        return (stable ? 1_000_000_000L : 0) + build.get("id").getAsInt();
    }
}
