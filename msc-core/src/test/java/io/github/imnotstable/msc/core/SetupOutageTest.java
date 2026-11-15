package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #589: a rerun of server_setup on a folder that has Geyser updates Geyser and the server. With PaperMC's and
 * GeyserMC's services both answering 503, it uses the cached builds and says so, instead of failing the setup. Fake
 * services only; the EULA is never accepted.
 */
class SetupOutageTest {
    @TempDir
    Path dir;

    @Test
    void aRerunWhileTheServicesAreDownUsesTheCachedJars() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        try (FakeDownloads downloads = FakeDownloads.start()) {
            try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
                h.ok("server_setup", "platform", "paper", "bedrock", true);
            }
            HttpServer down = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            down.createContext("/", ex -> {
                ex.sendResponseHeaders(503, -1);
                ex.close();
            });
            down.start();
            try {
                String url = "http://127.0.0.1:" + down.getAddress().getPort();
                try (CoreHarness h = new CoreHarness(ws, c -> downloads.apply(c).withDownloadsUrl(url + "/fill")
                        .withLoaderUrls(new LoaderInstaller.Urls(url, url, url, url, url + "/geyser")))) {
                    String again = h.ok("server_setup", "mode", "game");

                    assertThat(again).containsPattern("(?m)^Paper downloads answered 503 for \\S+; using the newest "
                                    + "cached build\npaper \\S+ build 5 \\(cached\\)$")
                            .containsPattern("(?m)^GeyserMC downloads answered 503 for \\S+/geyser/\\S+; using the "
                                    + "newest cached build$")
                            .contains("geyser build 1302 (already there), floodgate build 141 (already there)")
                            .contains("mode game: survival");
                }
            } finally {
                down.stop(0);
            }
        }
    }
}
