package io.github.imnotstable.msc.core.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstreamVersionsTest {
    @Test
    void theHttpFetcherReturnsABodyAndTurnsOtherAnswersIntoIOExceptions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/busy", ex -> {
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var http = new UpstreamVersions.Http(Duration.ofSeconds(5));
            assertThat(http.get(base + "/ok")).isEqualTo("{\"a\":1}");
            assertThatThrownBy(() -> http.get(base + "/busy"))
                    .isInstanceOf(IOException.class).hasMessage("HTTP 429 from 127.0.0.1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runPrintsOneLinePerComponentWithTheCoreDefaultMinecraft() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        UpstreamVersions.run(UpstreamCheckTest.CATALOG, UpstreamCheckTest.fetcher(UpstreamCheckTest.answers()),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(bytes.toString(StandardCharsets.UTF_8).lines())
                .contains("neoforge          26.3.0.102           26.3.0.103           NEWER")
                .last().asString().startsWith("5 newer, 0 could not check");
    }

    @Test
    void runOfflineStillPrintsEveryLine() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        UpstreamVersions.run(UpstreamCheckTest.CATALOG, UpstreamCheckTest.fetcher(Map.of()),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(bytes.toString(StandardCharsets.UTF_8).lines()).last().isEqualTo("0 newer, 8 could not check");
    }
}
