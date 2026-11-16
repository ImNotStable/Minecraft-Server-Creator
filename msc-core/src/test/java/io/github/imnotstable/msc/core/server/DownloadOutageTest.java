package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #589: every downloader with an update source follows {@link Downloads#orCached}. A source that is down (a 5xx, 429
 * or 408 answer, not only an unreachable host) means the newest cached build, with a one-line note; only with
 * nothing cached does it fail. A 404 is an answer, not an outage, and still fails.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DownloadOutageTest {
    @TempDir
    Path dir;

    private HttpServer http;

    /** What a downloader gave: the jar the server would use, and its note. */
    private record Got(Path jar, String note) {
    }

    @FunctionalInterface
    private interface Downloader {
        Got run(String base, Path cache, Path server) throws Exception;
    }

    /** Each downloader that updates from a source, with the cache name of the build it can fall back to. */
    private record Case(String name, String cachedJar, Downloader downloader) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Case> CASES = List.of(
            fill(Platform.PAPER, "26.3", 5), fill(Platform.FOLIA, "26.3", 3), fill(Platform.VELOCITY, "3.4.0", 45),
            new Case("purpur", "purpur-26.3-9.jar", (base, cache, server) -> {
                LoaderInstaller.Result r = new LoaderInstaller(new LoaderInstaller.Urls(base, base, base, base, base),
                        cache, "java").purpur("26.3", server);
                return new Got(server.resolve("purpur.jar"), r.note());
            }),
            geyser(GeyserDownloader.Project.GEYSER, 1302), geyser(GeyserDownloader.Project.FLOODGATE, 141));

    private static Case fill(Platform platform, String version, int build) {
        return new Case(platform.id(), platform.id() + "-" + version + "-" + build + ".jar", (base, cache, server) -> {
            PaperDownloader.Download d = new PaperDownloader(base, cache).download(platform, version);
            return new Got(d.jar(), d.note());
        });
    }

    private static Case geyser(GeyserDownloader.Project project, int build) {
        return new Case(project.id(), project.id() + "-spigot-" + build + ".jar", (base, cache, server) -> {
            GeyserDownloader.Download d = new GeyserDownloader(base, cache, DownloadStore.NONE).download(project);
            return new Got(d.jar(), d.note());
        });
    }

    static Stream<Arguments> downloadersAndOutages() {
        return CASES.stream().flatMap(c -> Stream.of(503, 502, 504, 429, 408).map(s -> Arguments.of(c, s)));
    }

    static Stream<Case> downloaders() {
        return CASES.stream();
    }

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    /** A server answering {@code status} to everything but the paths in {@code bodies}. */
    private String serve(int status, Map<String, String> bodies) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            String body = bodies.get(ex.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? status : 200, body == null ? -1 : bytes.length);
            if (body != null) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    private Path cached(Case c) throws IOException {
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.writeString(cache.resolve(c.cachedJar()), "cached " + c.name());
        return cache;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("downloadersAndOutages")
    void aSourceThatIsDownMeansTheCachedBuildWithANote(Case c, int status) throws Exception {
        Path cache = cached(c);
        String base = serve(status, Map.of());

        Got got = c.downloader().run(base, cache, dir.resolve("server"));

        assertThat(got.jar()).hasContent("cached " + c.name());
        assertThat(got.note()).matches("\\S.* answered " + status + " for http://127\\.0\\.0\\.1:\\d+/\\S+; "
                + "using the newest cached build");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("downloaders")
    void aSourceThatIsDownWithNothingCachedFailsInOneLine(Case c) throws Exception {
        String base = serve(503, Map.of());

        assertThatThrownBy(() -> c.downloader().run(base, dir.resolve("cache"), dir.resolve("server")))
                .isInstanceOf(ToolException.class).hasMessageContaining(" answered 503 for http://127.0.0.1:")
                .hasMessageNotContaining("\n").hasMessageNotContaining("cached");
    }

    /** A 404 says the version or project is not there: the cache does not paper over it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("downloaders")
    void aNotFoundAnswerIsNoOutage(Case c) throws Exception {
        Path cache = cached(c);
        String base = serve(404, Map.of());

        assertThatThrownBy(() -> c.downloader().run(base, cache, dir.resolve("server")))
                .isInstanceOf(ToolException.class).hasMessageNotContaining("cached");
    }

    /** The API names a newer build, but the jar's own download is down: the cached build, as for a down API. */
    @Test
    void aJarDownloadThatIsDownFallsBackToo() throws Exception {
        Case paper = CASES.getFirst();
        Path cache = cached(paper);
        String base = serve(503, Map.of());
        http.removeContext("/");
        http.createContext("/", ex -> {
            byte[] builds = ("[{\"id\":6,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"url\":\"" + base
                    + "/paper-6.jar\"}}}]").getBytes(StandardCharsets.UTF_8);
            boolean list = ex.getRequestURI().getPath().endsWith("/builds");
            ex.sendResponseHeaders(list ? 200 : 503, list ? builds.length : -1);
            if (list) {
                ex.getResponseBody().write(builds);
            }
            ex.close();
        });

        Got got = paper.downloader().run(base, cache, dir.resolve("server"));

        assertThat(got.jar()).isEqualTo(cache.resolve("paper-26.3-5.jar"));
        assertThat(got.note()).isEqualTo("download of " + base + "/paper-6.jar answered 503; using the newest cached "
                + "build");
        assertThat(cache.resolve("paper-26.3-6.jar")).doesNotExist();
    }

    /** Fabric and the loader installers download pinned versions only; down, they fail in one line, never oddly. */
    @Test
    void pinnedDownloadsFailInOneLineWhenTheSourceIsDown() throws Exception {
        String base = serve(503, Map.of());
        Path cache = dir.resolve("cache");
        FabricDownloader fabric = new FabricDownloader(base, base, cache);
        LoaderInstaller loaders = new LoaderInstaller(new LoaderInstaller.Urls(base, base, base, base, base), cache,
                "java");

        assertThatThrownBy(() -> fabric.launcher("26.3", "0.19.0")).isInstanceOf(ToolException.class)
                .hasMessage("Fabric meta answered 503 for " + base + "/v2/versions/installer");
        assertThatThrownBy(() -> fabric.api("0.1+26.3")).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("download of " + base + "/net/fabricmc/").hasMessageEndingWith(" answered 503");
        assertThatThrownBy(() -> loaders.neoforge("26.3.0.1", dir.resolve("server"))).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("download of " + base + "/net/neoforged/").hasMessageEndingWith(" answered 503");
    }
}
