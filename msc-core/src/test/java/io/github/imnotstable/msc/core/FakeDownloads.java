package io.github.imnotstable.msc.core;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Local fake download services for every platform server_setup installs (Paper's fill API, Purpur, Fabric meta and
 * maven, and the NeoForge, Forge and Quilt installers, all {@link FakeLoaderInstaller}), so a setup needs no network.
 */
final class FakeDownloads implements AutoCloseable {
    /** The Minecraft version the pinned Fabric API is for: the one every platform's setup accepts. */
    static final String MINECRAFT = minecraft();

    private final HttpServer http;
    private final String base;

    private FakeDownloads(HttpServer http) {
        this.http = http;
        this.base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    static FakeDownloads start() throws IOException {
        byte[] installer = FakeLoaderInstaller.jar();
        FakeDownloads d = new FakeDownloads(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
        String base = d.base;
        Map<String, String> texts = Map.of(
                "/fill/v3/projects/paper/versions/" + MINECRAFT + "/builds",
                "[{\"id\":5,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-" + MINECRAFT
                        + "-5.jar\",\"url\":\"" + base + "/fill/paper.jar\"}}}]",
                "/fill/paper.jar", "paper server",
                "/purpur/v2/purpur/" + MINECRAFT + "/latest", "{\"build\":\"9\"}",
                "/purpur/v2/purpur/" + MINECRAFT + "/9/download", "purpur server",
                "/meta/v2/versions/installer", "[{\"version\":\"1.1.2\",\"stable\":true}]",
                "/meta/v2/versions/loader/" + MINECRAFT + "/" + Templates.versions().get("fabricLoaderVersion")
                        + "/1.1.2/server/jar", "fabric server");
        d.http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            byte[] body;
            if (texts.containsKey(path)) {
                body = texts.get(path).getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/maven/net/fabricmc/fabric-api/fabric-api/") && path.endsWith(".jar")) {
                body = "fabric api".getBytes(StandardCharsets.UTF_8);
            } else if (path.matches("/(neo|forge|quilt)/.*installer.*\\.jar")) {
                body = installer;
            } else {
                body = null;
            }
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        d.http.start();
        return d;
    }

    /** {@code config} pointed at these services. */
    CoreConfig configure(CoreConfig config) {
        return config.withDownloadsUrl(base + "/fill").withFabricUrls(base + "/meta", base + "/maven")
                .withMinecraft(MINECRAFT).withLoaderUrls(new LoaderInstaller.Urls(base + "/neo", base + "/forge",
                        base + "/quilt", base + "/purpur"));
    }

    @Override
    public void close() {
        http.stop(0);
    }

    private static String minecraft() {
        String api = Templates.versions().get("fabricApiVersion");
        return api.substring(api.indexOf('+') + 1);
    }
}
