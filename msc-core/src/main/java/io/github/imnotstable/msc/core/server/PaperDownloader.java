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
 * Downloads a Paper server jar from PaperMC's downloads service (Fill, API v3) into a local cache.
 * The newest stable build of a version wins; without one, the newest build of any channel.
 */
public final class PaperDownloader {
    /** A downloaded jar. */
    public record Download(Path jar, String version, int build, boolean cached) {
    }

    private final String baseUrl;
    private final Path cacheDir;
    private final Downloads downloads = new Downloads();

    public PaperDownloader(String baseUrl, Path cacheDir) {
        this.baseUrl = Downloads.trim(baseUrl);
        this.cacheDir = cacheDir;
    }

    public Download download(String version) throws IOException, InterruptedException {
        URI builds = URI.create(baseUrl + "/v3/projects/paper/versions/" + version + "/builds");
        HttpResponse<String> response;
        try {
            response = downloads.text(builds);
        } catch (IOException e) {
            Path offline = newestCached(version);
            if (offline != null) {
                return new Download(offline, version, buildOf(offline), true);
            }
            throw new ToolException("cannot reach " + builds + ": " + e.getMessage());
        }
        if (response.statusCode() == 404) {
            throw new ToolException("Paper has no version " + version);
        }
        if (response.statusCode() != 200) {
            throw new ToolException("Paper downloads answered " + response.statusCode() + " for " + builds);
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
            throw new ToolException("Paper has no builds for " + version);
        }
        int build = best.get("id").getAsInt();
        JsonObject file = best.getAsJsonObject("downloads").getAsJsonObject("server:default");
        String url = file.get("url").getAsString();
        String sha256 = file.has("checksums") ? file.getAsJsonObject("checksums").get("sha256").getAsString() : null;
        Path jar = cacheDir.resolve("paper-" + version + "-" + build + ".jar");
        if (Files.exists(jar) && (sha256 == null || sha256.equalsIgnoreCase(Downloads.sha256(jar)))) {
            return new Download(jar, version, build, true);
        }
        downloads.fetch(URI.create(url), jar, "download of " + url + " answered 404", sha256);
        return new Download(jar, version, build, false);
    }

    private static long rank(JsonObject build) {
        boolean stable = build.has("channel") && "STABLE".equalsIgnoreCase(build.get("channel").getAsString());
        return (stable ? 1_000_000_000L : 0) + build.get("id").getAsInt();
    }

    private Path newestCached(String version) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return null;
        }
        try (var files = Files.list(cacheDir)) {
            return files.filter(p -> p.getFileName().toString().matches("paper-\\Q" + version + "\\E-\\d+\\.jar"))
                    .max((a, b) -> Integer.compare(buildOf(a), buildOf(b))).orElse(null);
        }
    }

    private static int buildOf(Path jar) {
        String n = jar.getFileName().toString();
        return Integer.parseInt(n.substring(n.lastIndexOf('-') + 1, n.length() - 4));
    }
}
