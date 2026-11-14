package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.HttpStall;
import io.github.imnotstable.msc.process.StallingHttpServer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #454: downloads give up on a server that goes silent after its headers, and keep going while bytes trickle in. */
class DownloadsTest {
    @TempDir
    Path cache;

    @Test
    void aDownloadThatStallsMidBodyFailsNamingTheHostAndLeavesNothing() throws Exception {
        try (StallingHttpServer server = new StallingHttpServer(100_000, 10, Duration.ZERO)) {
            Path jar = cache.resolve("paper.jar");
            assertThatThrownBy(() -> new Downloads(Duration.ofMillis(500))
                    .fetch(URI.create(server.url("/paper.jar")), jar, "gone", null))
                    .isInstanceOf(ToolException.class)
                    .hasMessage("download from 127.0.0.1 stalled (no data for 500 ms); check the network connection "
                            + "and run server_setup again");
            try (var left = Files.list(cache)) {
                assertThat(left).isEmpty();
            }
        }
    }

    @Test
    void aBuildListThatStallsFailsLikeAnUnreachableHost() throws Exception {
        try (StallingHttpServer server = new StallingHttpServer(100_000, 10, Duration.ZERO)) {
            assertThatThrownBy(() -> new Downloads(Duration.ofMillis(500)).text(URI.create(server.url("/builds"))))
                    .isInstanceOf(HttpStall.StalledException.class);
        }
    }

    @Test
    void aDownloadThatTricklesUnderTheStallLimitCompletes() throws Exception {
        try (StallingHttpServer server = new StallingHttpServer(10, 10, Duration.ofMillis(100))) {
            Path jar = cache.resolve("paper.jar");
            new Downloads(Duration.ofSeconds(3)).fetch(URI.create(server.url("/paper.jar")), jar, "gone", null);
            assertThat(jar).hasContent("x".repeat(10));
        }
    }
}
