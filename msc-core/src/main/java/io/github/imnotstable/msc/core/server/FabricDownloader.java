package io.github.imnotstable.msc.core.server;

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
 * Downloads what a Fabric dev server needs into a local cache: the server launcher (a small jar that
 * fetches the Minecraft server and libraries on first start) and the Fabric API mod.
 */
public final class FabricDownloader {
    /** A downloaded file. */
    public record Download(Path jar, String version, boolean cached) {
    }

    private final String metaUrl;
    private final String mavenUrl;
    private final Path cacheDir;
    private final Downloads downloads = new Downloads();

    public FabricDownloader(String metaUrl, String mavenUrl, Path cacheDir) {
        this.metaUrl = Downloads.trim(metaUrl);
        this.mavenUrl = Downloads.trim(mavenUrl);
        this.cacheDir = cacheDir;
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
        HttpResponse<String> response = Downloads.fresh(() -> downloads.text(uri, "Fabric meta"));
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
        Downloads.fresh(() -> downloads.fetch(uri, jar, notFound, null));
    }
}
