package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads a Paper server jar from PaperMC's downloads service (Fill, API v3) into a local cache.
 * The newest stable build of a version wins; without one, the newest build of any channel.
 */
public final class PaperDownloader {
    private static final String USER_AGENT = "msc-core/0.1 (Minecraft Server Creator dev tooling)";

    /** A downloaded jar. */
    public record Download(Path jar, String version, int build, boolean cached) {
    }

    private final String baseUrl;
    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    public PaperDownloader(String baseUrl, Path cacheDir) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.cacheDir = cacheDir;
    }

    public Download download(String version) throws IOException, InterruptedException {
        URI builds = URI.create(baseUrl + "/v3/projects/paper/versions/" + version + "/builds");
        HttpResponse<String> response;
        try {
            response = http.send(request(builds), HttpResponse.BodyHandlers.ofString());
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
        Files.createDirectories(cacheDir);
        Path jar = cacheDir.resolve("paper-" + version + "-" + build + ".jar");
        if (Files.exists(jar) && (sha256 == null || sha256.equalsIgnoreCase(sha256(jar)))) {
            return new Download(jar, version, build, true);
        }
        Path tmp = cacheDir.resolve(jar.getFileName() + ".part");
        HttpResponse<Path> got = http.send(request(URI.create(url)), HttpResponse.BodyHandlers.ofFile(tmp));
        if (got.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new ToolException("download of " + url + " answered " + got.statusCode());
        }
        if (sha256 != null && !sha256.equalsIgnoreCase(sha256(tmp))) {
            Files.deleteIfExists(tmp);
            throw new ToolException("checksum mismatch for " + url);
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
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

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).build();
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
