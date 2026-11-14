package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** #454 and #469: the stall rule, its timer reset by every byte, and cancelling on an interrupt. */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
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
    void aBodyThatTricklesForLongerThanTheStallLimitIsReadWholeBecauseEveryByteResetsTheTimer() throws Exception {
        // 20 bytes, 100 ms apart: at least 2 s in all, well over the 750 ms limit, while each gap stays far under it.
        Duration limit = Duration.ofMillis(750);
        try (StallingHttpServer server = new StallingHttpServer(20, 20, Duration.ofMillis(100));
             HttpClient http = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            HttpResponse<String> got = HttpStall.send(http, get(server.url("/x.jar")),
                    HttpResponse.BodyHandlers.ofString(), limit, "run it again");
            assertThat(got.body()).isEqualTo("x".repeat(20));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(limit.multipliedBy(2));
        }
    }

    @Test
    void anInterruptCancelsTheRequest() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (StallingHttpServer server = new StallingHttpServer(100_000, 10, Duration.ZERO)) {
            FutureTask<HttpResponse<String>> send = new FutureTask<>(() -> HttpStall.send(http,
                    get(server.url("/x.jar")), HttpResponse.BodyHandlers.ofString(), Duration.ofMinutes(5),
                    "run it again"));
            Thread sender = Thread.ofVirtual().start(send);
            assertThat(server.answered(Duration.ofSeconds(30))).isTrue();
            sender.interrupt();
            assertThatThrownBy(() -> send.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            // The interrupt must cancel the exchange: the server sees the connection close.
            assertThat(server.hungUp(Duration.ofSeconds(30))).isTrue();
        } finally {
            // Not close(): it waits for an exchange that was not cancelled, so a regression would hang here.
            http.shutdownNow();
        }
    }
}
