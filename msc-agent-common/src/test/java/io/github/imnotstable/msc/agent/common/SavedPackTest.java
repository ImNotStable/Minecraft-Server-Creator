package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A server started without MSC serves the pack MSC saved beside it (ADR 0076). */
class SavedPackTest {
    private static final AgentLog LOG = AgentLog.of(System.getLogger("saved-pack-test"));

    @Test
    void servesTheSavedPackAndSendsItToJoiners(@TempDir Path server) throws Exception {
        byte[] zip = {'P', 'K', 3, 4, 1, 2, 3};
        Files.createDirectories(server.resolve(SavedPack.DIR));
        Files.write(server.resolve(SavedPack.DIR).resolve("pack.zip"), zip);
        Files.writeString(server.resolve(SavedPack.DIR).resolve("host"), "127.0.0.1\n");
        ServerOps ops = new ServerOps(null, null);
        try (SavedPack pack = SavedPack.serve(server, ops, LOG).orElseThrow()) {
            AgentServer.Pack offer = ops.currentPack().orElseThrow();
            assertThat(offer.url()).isEqualTo(pack.url()).startsWith("http://127.0.0.1:");
            assertThat(offer.sha1()).isEqualTo(SavedPack.sha1(zip));
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<byte[]> got = http.send(HttpRequest.newBuilder(URI.create(pack.url())).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(got.statusCode()).isEqualTo(200);
                assertThat(got.body()).isEqualTo(zip);
                URI other = URI.create(pack.url().replace("/pack-", "/other-"));
                assertThat(http.send(HttpRequest.newBuilder(other).build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode()).isEqualTo(404);
            }
        }
    }

    @Test
    void noSavedPackServesNothing(@TempDir Path server) {
        ServerOps ops = new ServerOps(null, null);
        assertThat(SavedPack.serve(server, ops, LOG)).isEmpty();
        assertThat(ops.currentPack()).isEmpty();
    }
}
