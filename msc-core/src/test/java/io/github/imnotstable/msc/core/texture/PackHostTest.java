package io.github.imnotstable.msc.core.texture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.textures.PackSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.io.TempDir;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class PackHostTest {
    @Test
    void waitsForAPortThatIsLetGoAMomentLater() throws Exception {
        ServerSocket holder = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        int port = holder.getLocalPort();
        CompletableFuture.runAsync(() -> {
            try {
                holder.close();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
        PackHost.Host server = PackHost.startWithin("127.0.0.1", port, () -> PackHost.Served.NONE,
                Duration.ofSeconds(5));
        try {
            assertThat(server.port()).isEqualTo(port);
        } finally {
            server.stop();
        }
    }

    @Test
    void givesUpOnAPortThatStaysTaken() throws Exception {
        try (ServerSocket holder = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> PackHost.startWithin("127.0.0.1", holder.getLocalPort(),
                    () -> PackHost.Served.NONE, Duration.ofMillis(300))).isInstanceOf(BindException.class);
        }
    }

    /** Big enough that a client which stops reading leaves the host's write blocked. */
    private static final PackHost.Served BIG = served(32 * 1024 * 1024, 1);
    private static final Duration NEVER = Duration.ofHours(1);

    @Test
    void aClientThatStopsReadingDoesNotHoldUpAnotherPlayersDownload() throws Exception {
        PackHost.Host host = PackHost.start("127.0.0.1", 0, () -> BIG, NEVER);
        try (Socket stalled = stalledDownload(host, BIG)) {
            assertThat(download(host, BIG)).isEqualTo(BIG.data());
            assertThat(stalled.isClosed()).isFalse();
        } finally {
            host.stop();
        }
    }

    /** #512: clients that send part of a request and then nothing hold none of the download turns. */
    @Test
    void halfSentRequestsDoNotHoldUpADownload() throws Exception {
        PackHost.Served pack = served(1000, 4);
        PackHost.Host host = PackHost.start("127.0.0.1", 0, () -> pack, NEVER);
        List<Socket> halfSent = new ArrayList<>();
        try {
            for (int i = 0; i < PackHost.MAX_DOWNLOADS * 2; i++) {
                halfSent.add(halfSentRequest(host));
            }
            assertThat(download(host, pack)).isEqualTo(pack.data());
            assertThat(halfSent).noneMatch(Socket::isClosed);
        } finally {
            for (Socket s : halfSent) {
                s.close();
            }
            host.stop();
        }
    }

    @Test
    void aHalfSentRequestIsClosedAfterTheStallLimit() throws Exception {
        PackHost.Served pack = served(1000, 5);
        PackHost.Host host = PackHost.start("127.0.0.1", 0, () -> pack, Duration.ofMillis(200));
        try (Socket halfSent = halfSentRequest(host)) {
            while (host.unfinished() == 0) {
                Thread.sleep(20); // polls state; the class timeout fails a host that never cuts it
            }
            assertThat(readToEnd(halfSent)).isZero();
            assertThat(download(host, pack)).isEqualTo(pack.data());
            assertThat(host.stalled()).isZero();
        } finally {
            host.stop();
        }
    }

    /** Opens a connection and sends the first byte of a request, and nothing more. */
    private static Socket halfSentRequest(PackHost.Host host) throws IOException {
        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", host.port()));
        socket.getOutputStream().write('G');
        socket.getOutputStream().flush();
        return socket;
    }

    @Test
    void parallelDownloadsOfTheStandaloneHostsFileAllComplete(@TempDir Path dir) throws Exception {
        PackHost.Served pack = served(2 * 1024 * 1024, 2);
        Files.write(dir.resolve("pack-" + pack.sha1() + ".zip"), pack.data());
        PackHost.Host host = PackHost.start("127.0.0.1", 0, PackHost.folder(dir), NEVER);
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<byte[]>> downloads = new ArrayList<>();
            for (int i = 0; i < PackHost.MAX_DOWNLOADS + 4; i++) {
                downloads.add(clients.submit(() -> download(host, pack)));
            }
            for (Future<byte[]> d : downloads) {
                assertThat(d.get()).isEqualTo(pack.data());
            }
        } finally {
            host.stop();
        }
    }

    @Test
    void servesThePackUnderItsHashAndNothingElse() throws Exception {
        PackHost.Served pack = served(1000, 3);
        PackHost.Host host = PackHost.start("127.0.0.1", 0, () -> pack);
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> hit = http.send(HttpRequest.newBuilder(url(host, pack.sha1())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(hit.statusCode()).isEqualTo(200);
            assertThat(hit.headers().firstValue("Content-Type")).contains("application/zip");
            assertThat(hit.body()).isEqualTo(pack.data());
            HttpResponse<byte[]> miss = http.send(HttpRequest.newBuilder(url(host, "0".repeat(40))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(miss.statusCode()).isEqualTo(404);
        } finally {
            host.stop();
        }
    }

    /**
     * ADR 0053 (#753): the standalone host serves every build file in its folder by its name, so a later build (another
     * session's) leaves an earlier one's URL served; a missing file, or one whose content is not its name, is a 404.
     */
    @Test
    void theStandaloneHostServesEachBuildFileByItsHash(@TempDir Path dir) throws Exception {
        PackHost.Served first = served(1000, 4);
        PackHost.Served second = served(1000, 5);
        PackHost.Served forged = served(1000, 6);
        Files.write(dir.resolve("pack-" + first.sha1() + ".zip"), first.data());
        Files.write(dir.resolve("pack-" + forged.sha1() + ".zip"), first.data());
        PackHost.Host host = PackHost.start("127.0.0.1", 0, PackHost.folder(dir), NEVER);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThat(status(http, host, second.sha1())).isEqualTo(404);
            Files.write(dir.resolve("pack-" + second.sha1() + ".zip"), second.data());
            HttpResponse<byte[]> later = http.send(HttpRequest.newBuilder(url(host, second.sha1())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(later.body()).isEqualTo(second.data());
            HttpResponse<byte[]> earlier = http.send(HttpRequest.newBuilder(url(host, first.sha1())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(earlier.body()).isEqualTo(first.data());
            assertThat(status(http, host, forged.sha1())).isEqualTo(404);
        } finally {
            host.stop();
        }
    }

    /**
     * #753: a pack port is picked below the ephemeral range, which the system hands to binds to port 0 and to outgoing
     * connections: there, any process could take it while it goes from one host to the next.
     */
    @Test
    void aFreePackPortIsNeverOneTheSystemHandsOutByItself() throws Exception {
        assertThat(PackHost.freePortRange(32768)).containsExactly(20000, 32768);
        assertThat(PackHost.freePortRange(49152)).containsExactly(20000, 32768);
        assertThat(PackHost.freePortRange(10000)).containsExactly(1024, 10000);
        assertThat(PackHost.freePortRange(1500)).isNull();
        int[] pick = PackHost.freePortRange(PackHost.ephemeralStart());
        org.junit.jupiter.api.Assumptions.assumeTrue(pick != null, "this system hands out every port by itself");
        for (int i = 0; i < 5; i++) {
            PackHost.Host host = PackHost.startOnFreePort("127.0.0.1", () -> PackHost.Served.NONE, p -> false, null);
            try {
                assertThat(host.port()).isBetween(pick[0], pick[1] - 1);
            } finally {
                host.stop();
            }
        }
    }

    private static int status(HttpClient http, PackHost.Host host, String sha1) throws Exception {
        return http.send(HttpRequest.newBuilder(url(host, sha1)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    void aStalledConnectionIsClosedAfterTheStallLimit() throws Exception {
        PackHost.Host host = PackHost.start("127.0.0.1", 0, () -> BIG, Duration.ofMillis(200));
        try (Socket stalled = stalledDownload(host, BIG)) {
            while (host.stalled() == 0) {
                Thread.sleep(20); // polls state; the class timeout fails a host that never cuts it
            }
            assertThat(readToEnd(stalled)).isLessThan(BIG.data().length);
        } finally {
            host.stop();
        }
    }

    @Test
    void stopReturnsWithAStalledClientConnectedAndEndsItsConnection() throws Exception {
        PackHost.Host host = PackHost.start("127.0.0.1", 0, () -> BIG, NEVER);
        try (Socket stalled = stalledDownload(host, BIG)) {
            CompletableFuture.runAsync(host::stop).get(60, TimeUnit.SECONDS);
            assertThat(readToEnd(stalled)).isLessThan(BIG.data().length);
        }
    }

    private static PackHost.Served served(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return new PackHost.Served(data, PackSource.sha1(data));
    }

    private static URI url(PackHost.Host host, String sha1) {
        return URI.create("http://127.0.0.1:" + host.port() + "/pack-" + sha1 + ".zip");
    }

    private static byte[] download(PackHost.Host host, PackHost.Served pack) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(url(host, pack.sha1())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    /** Asks for the pack, reads the status line (so the host is sending it) and then reads nothing more. */
    private static Socket stalledDownload(PackHost.Host host, PackHost.Served pack) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(4096);
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", host.port()));
        OutputStream out = socket.getOutputStream();
        out.write(("GET /pack-" + pack.sha1() + ".zip HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder status = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            status.append((char) c);
        }
        assertThat(status.toString()).startsWith("HTTP/1.1 200");
        return socket;
    }

    /** Bytes that still arrive until the host's end of the connection is closed. */
    private static long readToEnd(Socket socket) {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try {
            InputStream in = socket.getInputStream();
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
            }
        } catch (IOException e) {
            // A reset ends it as well as a close.
        }
        return total;
    }

    /**
     * #755, ADR 0054: the pick never returns a port the workspace uses otherwise, for any random source and any set of
     * reserved ports, among them the ports MSC itself uses (25565, 25575, 25577) right inside the range. A table of
     * seeds and reserved sets, from a few ports to all but one.
     */
    @Test
    void aFreePortPickNeverReturnsAReservedPort() {
        int[] range = {20000, 32768};
        java.util.List<java.util.Set<Integer>> sets = new ArrayList<>();
        sets.add(java.util.Set.of(25565, 25575, 25577, 19132));
        java.util.Set<Integer> block = new java.util.HashSet<>();
        for (int p = 25000; p < 26000; p++) {
            block.add(p);
        }
        sets.add(block);
        java.util.Set<Integer> allButOne = new java.util.HashSet<>();
        for (int p = range[0]; p < range[1]; p++) {
            allButOne.add(p);
        }
        allButOne.remove(31000);
        sets.add(allButOne);
        for (long seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            java.util.Set<Integer> some = new java.util.HashSet<>(java.util.Set.of(25565, 25577));
            for (int i = 0; i < 500; i++) {
                some.add(range[0] + random.nextInt(range[1] - range[0]));
            }
            sets.add(some);
        }
        for (int s = 0; s < sets.size(); s++) {
            java.util.Set<Integer> reserved = sets.get(s);
            java.util.random.RandomGenerator random = new Random(s);
            for (int i = 0; i < 2_000; i++) {
                int port = PackHost.pick(range, reserved::contains, random);
                assertThat(port).as("set %d, pick %d", s, i).isBetween(range[0], range[1] - 1);
                assertThat(reserved).as("set %d, pick %d", s, i).doesNotContain(port);
            }
        }
        // Every pick lands on 25565 first: the next port that is not reserved is taken instead.
        java.util.random.RandomGenerator onServerPort = always(25565 - range[0]);
        assertThat(PackHost.pick(range, java.util.Set.of(25565, 25566)::contains, onServerPort)).isEqualTo(25567);
        // At the top of the range, it wraps around.
        assertThat(PackHost.pick(range, java.util.Set.of(32767)::contains, always(32767 - range[0])))
                .isEqualTo(20000);
        assertThat(PackHost.pick(new int[] {25565, 25567}, java.util.Set.of(25565)::contains, always(0)))
                .isEqualTo(25566);
        assertThat(PackHost.pick(new int[] {25565, 25567}, p -> true, always(0))).isEqualTo(-1);
    }

    /**
     * #755: a host started on a free port with a random source that lands on the server's and the proxy's ports first
     * never serves there, and neither does the system's own pick (port 0) when it is reserved.
     */
    @Test
    void aHostOnAFreePortNeverTakesAReservedPort() throws Exception {
        int[] range = PackHost.freePortRange(PackHost.ephemeralStart());
        org.junit.jupiter.api.Assumptions.assumeTrue(range != null, "this system hands out every port by itself");
        java.util.Set<Integer> reserved = java.util.Set.of(25565, 25577);
        for (int at : new int[] {25565, 25577}) {
            org.junit.jupiter.api.Assumptions.assumeTrue(at >= range[0] && at < range[1], "range holds " + at);
            PackHost.Host host = PackHost.startOnFreePort("127.0.0.1", PackHost.one(() -> PackHost.Served.NONE),
                    reserved::contains, always(at - range[0]), null);
            try {
                assertThat(host.port()).isNotIn(25565, 25577);
            } finally {
                host.stop();
            }
        }
    }

    /** A random source whose every int below a bound is {@code value}. */
    private static java.util.random.RandomGenerator always(int value) {
        return new java.util.random.RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    /**
     * ADR 0054: every host says who it is on its identity path, the pid of its process and its token; a port with no
     * host, or with something that is not one, has no identity.
     */
    @Test
    void aHostSaysWhoItIsAndNothingElseDoes() throws Exception {
        PackHost.Host host = PackHost.start("127.0.0.1", 0, PackHost.one(() -> PackHost.Served.NONE), NEVER, "abc123");
        try {
            assertThat(PackHost.identify("127.0.0.1", host.port()))
                    .isEqualTo(new PackHost.Identity(ProcessHandle.current().pid(), "abc123"));
            assertThat(host.token()).isEqualTo("abc123");
        } finally {
            host.stop();
        }
        PackHost.Host fresh = PackHost.start("127.0.0.1", 0, () -> PackHost.Served.NONE);
        try {
            assertThat(PackHost.identify("127.0.0.1", fresh.port()).token()).isEqualTo(fresh.token())
                    .isNotEqualTo("abc123");
        } finally {
            fresh.stop();
        }
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try (Socket s = silent.accept(); OutputStream out = s.getOutputStream()) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    // The test ends it.
                }
            });
            assertThat(PackHost.identify("127.0.0.1", silent.getLocalPort())).isNull();
        }
        assertThat(PackHost.identify("127.0.0.1", host.port())).isNull();
    }
}
