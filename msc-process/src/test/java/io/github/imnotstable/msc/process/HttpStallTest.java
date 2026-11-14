package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpStallTest {
    private static HttpRequest get(String url) {
        // The JDK's own timeout, which covers only the wait for the headers, as the product's requests set it.
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build();
    }

    @Test
    void aBodyThatStopsComingFailsAfterTheStallLimitAndHangsUp() throws Exception {
        try (StallingHttpServer server = new StallingHttpServer(100_000, 10, Duration.ZERO);
             HttpClient http = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> HttpStall.send(http, get(server.url("/x.jar")),
                    HttpResponse.BodyHandlers.ofString(), Duration.ofMillis(500), "run it again"))
                    .isInstanceOf(HttpStall.StalledException.class)
                    .hasMessage("download from 127.0.0.1 stalled (no data for 500 ms); check the network connection "
                            + "and run it again");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
            assertThat(server.hungUp(Duration.ofSeconds(30))).isTrue();
        }
    }

    @Test
    void aBodyThatTricklesUnderTheStallLimitIsReadWhole() throws Exception {
        try (StallingHttpServer server = new StallingHttpServer(10, 10, Duration.ofMillis(100));
             HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> got = HttpStall.send(http, get(server.url("/x.jar")),
                    HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(3), "run it again");
            assertThat(got.body()).isEqualTo("x".repeat(10));
        }
    }

    @Test
    void anInterruptCancelsTheRequest() throws Exception {
        try (StallingHttpServer server = new StallingHttpServer(100_000, 10, Duration.ZERO);
             HttpClient http = HttpClient.newHttpClient()) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> HttpStall.send(http, get(server.url("/x.jar")),
                    HttpResponse.BodyHandlers.ofString(), Duration.ofMinutes(5), "run it again"))
                    .isInstanceOf(InterruptedException.class);
        }
    }
}
