package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Downloads what a Fabric dev server needs into a local cache: the server launcher (a small jar that
 * fetches the Minecraft server and libraries on first start) and the Fabric API mod.
 */
public final class FabricDownloader {
    private static final String USER_AGENT = "msc-core/0.1 (Minecraft Server Creator dev tooling)";

    /** A downloaded file. */
    public record Download(Path jar, String version, boolean cached) {
    }

    private final String metaUrl;
    private final String mavenUrl;
    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    public FabricDownloader(String metaUrl, String mavenUrl, Path cacheDir) {
        this.metaUrl = trim(metaUrl);
        this.mavenUrl = trim(mavenUrl);
        this.cacheDir = cacheDir;
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The server launcher for a Minecraft and loader version, built by the newest stable installer. */
    public Download launcher(String minecraft, String loader) throws IOException, InterruptedException {
        Path jar = cacheDir.resolve("fabric-server-" + minecraft + "-" + loader + ".jar");
        if (Files.exists(jar)) {
            return new Download(jar, loader, true);
        }
        String installer = installerVersion();
        fetch(URI.create(metaUrl + "/v2/versions/loader/" + minecraft + "/" + loader + "/" + installer + "/server/jar"),
                jar, "Fabric has no server launcher for Minecraft " + minecraft + " with loader " + loader);
        return new Download(jar, loader, false);
    }

    /** The Fabric API mod jar. */
    public Download api(String version) throws IOException, InterruptedException {
        Path jar = cacheDir.resolve("fabric-api-" + version + ".jar");
        if (Files.exists(jar)) {
            return new Download(jar, version, true);
        }
        fetch(URI.create(mavenUrl + "/net/fabricmc/fabric-api/fabric-api/" + version + "/fabric-api-" + version + ".jar"),
                jar, "Fabric API " + version + " is not in " + mavenUrl);
        return new Download(jar, version, false);
    }

    private String installerVersion() throws IOException, InterruptedException {
        URI uri = URI.create(metaUrl + "/v2/versions/installer");
        HttpResponse<String> response;
        try {
            response = http.send(request(uri), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ToolException("cannot reach " + uri + ": " + e.getMessage());
        }
        if (response.statusCode() != 200) {
            throw new ToolException("Fabric meta answered " + response.statusCode() + " for " + uri);
        }
        String newest = null;
        for (JsonElement e : JsonParser.parseString(response.body()).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String version = o.get("version").getAsString();
            if (newest == null) {
                newest = version;
            }
            if (o.has("stable") && o.get("stable").getAsBoolean()) {
                return version;
            }
        }
        if (newest == null) {
            throw new ToolException("Fabric meta lists no installer versions");
        }
        return newest;
    }

    private void fetch(URI uri, Path jar, String notFound) throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Path tmp = cacheDir.resolve(jar.getFileName() + ".part");
        HttpResponse<Path> got;
        try {
            got = http.send(request(uri), HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new ToolException("cannot reach " + uri + ": " + e.getMessage());
        }
        if (got.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new ToolException(got.statusCode() == 404 ? notFound
                    : "download of " + uri + " answered " + got.statusCode());
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).build();
    }
}
