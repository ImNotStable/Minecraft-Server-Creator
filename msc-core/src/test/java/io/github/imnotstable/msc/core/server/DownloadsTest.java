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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** #454: downloads give up on a server that goes silent after its headers, and keep going while bytes trickle in. */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
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
    void aDownloadThatTricklesForLongerThanTheStallLimitCompletes() throws Exception {
        // At least 2 s in all against a 750 ms limit; each 100 ms gap stays far under it.
        try (StallingHttpServer server = new StallingHttpServer(20, 20, Duration.ofMillis(100))) {
            Path jar = cache.resolve("paper.jar");
            new Downloads(Duration.ofMillis(750)).fetch(URI.create(server.url("/paper.jar")), jar, "gone", null);
            assertThat(jar).hasContent("x".repeat(20));
        }
    }

    @Test
    void aChecksumIsComparedWithoutTheWhitespaceAroundIt() {
        assertThat(Downloads.Checksum.of("MD5", " d062b7110636df72b4e22b7aaed97484\n").hex())
                .isEqualTo("d062b7110636df72b4e22b7aaed97484");
        assertThat(Downloads.Checksum.of("MD5", null)).isNull();
    }
}
