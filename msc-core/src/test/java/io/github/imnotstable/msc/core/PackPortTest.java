package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.Platform;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #755, ADR 0054: the pack never takes a port the workspace uses for anything else. A remembered pack port that is now
 * such a port is dropped, a fixed {@code MSC_PACK_PORT} on one is refused by name, and a start that finds its port held
 * by this session's own pack host says so and moves the pack off it.
 */
class PackPortTest {
    @TempDir
    Path ws;
    private CoreHarness h;

    @AfterEach
    void close() throws Exception {
        if (h != null) {
            h.close();
        }
    }

    private CoreHarness session(int packPort) throws Exception {
        Files.createDirectories(ws.resolve("demo"));
        Files.writeString(ws.resolve("demo/version.txt"), "1.0");
        return new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)).withPackPort(packPort));
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    private String pack() {
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#102030"), "rows", List.of("a"));
        String answer = h.ok("pack");
        return answer.replaceAll("(?s).*\nurl: (\\S+).*", "$1");
    }

    private static int port(String url) {
        return Integer.parseInt(url.replaceAll("http://[^:]+:(\\d+)/.*", "$1"));
    }

    private String remembered() throws Exception {
        Path file = ws.resolve(".msc/pack-port");
        return Files.exists(file) ? Files.readString(file).strip() : "none";
    }

    /** The issue's case: the pick of an earlier session was the server's own default port, 25565. */
    @Test
    void aRememberedPackPortOnTheServersDefaultPortIsDropped() throws Exception {
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), "motd=x\n");
        Files.createDirectories(ws.resolve(".msc"));
        Files.writeString(ws.resolve(".msc/pack-port"), "25565\n");
        h = session(0);
        int port = port(pack());
        assertThat(port).isNotIn(25565, 25575, 25577, 19132);
        assertThat(remembered()).isEqualTo(Integer.toString(port));
    }

    /** A port the server folder sets (here server-port and rcon.port) is reserved as the defaults are. */
    @Test
    void aRememberedPackPortTheServerPropertiesNowSetIsDropped() throws Exception {
        int server = freePort();
        int rcon = freePort();
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), "server-port=" + server + "\nrcon.port=" + rcon + "\n");
        Files.createDirectories(ws.resolve(".msc"));
        Files.writeString(ws.resolve(".msc/pack-port"), server + "\n");
        h = session(0);
        int first = port(pack());
        assertThat(first).isNotIn(server, rcon, 25565, 25577);
        assertThat(remembered()).isEqualTo(Integer.toString(first));
        h.close();
        // The next session takes the remembered port again while it is not reserved, and drops it once it is.
        Files.writeString(ws.resolve(".msc/pack-port"), rcon + "\n");
        h = session(0);
        int second = port(pack());
        assertThat(second).isNotIn(server, rcon);
    }

    @Test
    void aFixedPackPortTheWorkspaceUsesIsRefusedByName() throws Exception {
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), "motd=x\n");
        h = session(25577);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#102030"), "rows", List.of("a"));
        assertThat(h.error("pack")).contains("MSC_PACK_PORT=25577 is the dev proxy's port")
                .contains("Set MSC_PACK_PORT to another port");
        h.close();
        int server = freePort();
        Files.writeString(ws.resolve("run/server.properties"), "server-port=" + server + "\n");
        h = session(server);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#102030"), "rows", List.of("a"));
        assertThat(h.error("pack")).contains("MSC_PACK_PORT=" + server + " is the server's port (server-port in "
                + "server.properties)");
    }

    /**
     * A port the core cannot know (here a fake server binding the port in bind-port.txt, as a Minestom main class may)
     * held by this session's own pack host: the start's answer names that host, not "a leftover", and the host moves
     * off the port, so the next start binds it.
     */
    @Test
    void aStartWhosePortThisSessionsPackHostHoldsSaysSoAndMovesThePack() throws Exception {
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), "motd=x\n");
        h = session(0);
        Platform.remember(h.core.config(), Platform.PAPER);
        int held = port(pack());
        Files.writeString(ws.resolve("run/bind-port.txt"), held + "\n");
        String failed = h.ok("server_start", "timeoutSeconds", 60);
        assertThat(failed).contains("server port " + held + " already in use")
                .contains("this session's resource pack host held port " + held + " and has moved to a free one")
                .doesNotContain("server_stop stops a leftover server");
        String started = h.ok("server_start", "timeoutSeconds", 60);
        assertThat(started).contains("server ready");
        int moved = port(pack());
        assertThat(moved).isNotEqualTo(held);
        h.ok("server_stop");
    }

    /** A port that is free now, picked from where the pack's own picks never are (above the ephemeral start). */
    private static int freePort() throws Exception {
        for (int i = 0; i < 100; i++) {
            int port = 40000 + ThreadLocalRandom.current().nextInt(20000);
            try (ServerSocket s = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
                return s.getLocalPort();
            } catch (java.io.IOException e) {
                // Taken: another.
            }
        }
        throw new AssertionError("no free port");
    }
}
