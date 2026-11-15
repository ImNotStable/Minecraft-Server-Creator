package io.github.imnotstable.msc.core;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Local stand-ins for every download service server_setup uses (Paper, Folia and Velocity, Purpur, Fabric meta and
 * Maven, and the NeoForge, Forge and Quilt installers, which are {@link FakeLoaderInstaller}). No real download, no real server.
 */
final class FakeDownloads implements AutoCloseable {
    /** The Minecraft version the fakes serve: the one the templates' Fabric API is built for. */
    static final String MINECRAFT = minecraft();
    /** The Velocity version server_setup installs: the pinned one. */
    static final String VELOCITY = Templates.versions().get("velocityVersion");

    private final HttpServer http;
    private final String base;

    private FakeDownloads(HttpServer http) {
        this.http = http;
        this.base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    static FakeDownloads start() throws IOException {
        byte[] installer = FakeLoaderInstaller.jar();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeDownloads fake = new FakeDownloads(http);
        String base = fake.base;
        Map<String, String> texts = Map.of(
                "/fill/v3/projects/paper/versions/" + MINECRAFT + "/builds",
                "[{\"id\":5,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-" + MINECRAFT
                        + "-5.jar\",\"url\":\"" + base + "/fill/paper.jar\"}}}]",
                "/fill/paper.jar", "paper server",
                "/fill/v3/projects/folia/versions/" + MINECRAFT + "/builds", build(base, "folia", 3),
                "/fill/folia.jar", "folia server",
                "/fill/v3/projects/velocity/versions/" + VELOCITY + "/builds", build(base, "velocity", 45),
                "/fill/velocity.jar", "velocity server",
                "/purpur/v2/purpur/" + MINECRAFT + "/latest", "{\"build\":\"9\"}",
                "/purpur/v2/purpur/" + MINECRAFT + "/9/download", "purpur server",
                "/meta/v2/versions/installer", "[{\"version\":\"1.1.2\",\"stable\":true}]",
                "/meta/v2/versions/loader/" + MINECRAFT + "/" + Templates.versions().get("fabricLoaderVersion")
                        + "/1.1.2/server/jar", "fabric server");
        http.createContext("/", exchange -> {
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
        http.start();
        return fake;
    }

    /** {@code c} downloading everything from these fakes. */
    CoreConfig apply(CoreConfig c) {
        return c.withDownloadsUrl(base + "/fill").withFabricUrls(base + "/meta", base + "/maven")
                .withMinecraft(MINECRAFT).withLoaderUrls(new LoaderInstaller.Urls(base + "/neo", base + "/forge",
                        base + "/quilt", base + "/purpur"));
    }

    @Override
    public void close() {
        http.stop(0);
    }

    /** A Fill builds answer with one stable build of {@code project}, served at {@code /fill/<project>.jar}. */
    private static String build(String base, String project, int id) {
        return "[{\"id\":" + id + ",\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"" + project
                + "-" + id + ".jar\",\"url\":\"" + base + "/fill/" + project + ".jar\"}}}]";
    }

    private static String minecraft() {
        String api = Templates.versions().get("fabricApiVersion");
        return api.substring(api.indexOf('+') + 1);
    }
}
