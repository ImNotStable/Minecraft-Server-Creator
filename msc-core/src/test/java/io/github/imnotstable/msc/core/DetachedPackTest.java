package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FailingPackHostMain;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.texture.PackHost;
import io.github.imnotstable.msc.core.texture.PackProperties;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #741: a pack built or rebuilt after server_detach was served in-process only, so a switch away, the switch back and
 * the session end left the detached server offering a URL nothing served. Once a server is detached, the standalone
 * host serves every build. PackLifecycleOrderTest covers the orders of these calls (ADR 0051).
 */
class DetachedPackTest {
    @TempDir
    Path workspace;
    private CoreHarness h;

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @BeforeEach
    void start() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)));
    }

    /** The session ends; the detached server and its pack host, which outlive it, are stopped here. */
    @AfterEach
    void stop() throws Exception {
        h.close();
        for (String file : List.of("server.pid", "pack-host.pid")) {
            Path pid = workspace.resolve(".msc/" + file);
            if (Files.exists(pid)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pid).split(" ")[0].strip())).ifPresent(p -> {
                    p.destroyForcibly();
                    p.onExit().orTimeout(10, TimeUnit.SECONDS).join();
                });
            }
        }
    }

    private String pixel(String color) {
        return h.ok("texture", "name", "item/dot", "palette", List.of("a=#" + color), "rows", List.of("a"));
    }

    /** True when {@code url} serves the pack now (a 200). */
    private static boolean serves(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try {
                return c.getResponseCode() == 200;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code url} is served after a switch away, after the switch back and after the session ends. */
    private void servedThroughSwitchesAndTheSessionEnd(String url) throws Exception {
        assertThat(workspace.resolve(".msc/pack-host.pid")).as("the standalone host serves it").exists();
        assertThat(serves(url)).as("after the build").isTrue();
        h.ok("msc_workspace", "path", "other", "create", true);
        assertThat(serves(url)).as("while switched away").isTrue();
        assertThat(h.ok("msc_workspace", "path", workspace.toString()))
                .contains("; the server this session detached here runs on, still this session's");
        assertThat(serves(url)).as("after the switch back").isTrue();
        h.close();
        assertThat(serves(url)).as("after the session end").isTrue();
    }

    @Test
    void aPackBuiltAfterTheDetachOutlivesASwitchAndTheSession() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(h.ok("server_detach")).contains("(no pack built yet; the pack tool starts a pack host that does too)");
        pixel("ff0000");
        String built = h.ok("pack");
        assertThat(built).contains("applied: sent to ");
        servedThroughSwitchesAndTheSessionEnd(built.replaceAll("(?s).*\nurl: (\\S+).*", "$1"));
    }

    @Test
    void aPackRebuiltByDevCycleWhileDetachedOutlivesASwitchAndTheSession() throws Exception {
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("server ready");
        h.ok("server_detach");
        pixel("ff0000");
        String first = h.ok("pack").replaceAll("(?s).*\nurl: (\\S+).*", "$1");
        pixel("00ff00");
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false))
                .contains("\nresource pack sent: players who join get it");
        String rebuilt = h.core.pack().lastBuilt().url();
        assertThat(rebuilt).isNotEqualTo(first);
        servedThroughSwitchesAndTheSessionEnd(rebuilt);
    }

    /**
     * #744: a standalone host that does not come up never fails the call. The pack is served and sent by this session,
     * every answer says so with the reason in one line (never a stack trace line), server.properties does not offer a
     * pack that dies with the session, and the next call tries the host again.
     */
    @Test
    void aStandaloneHostThatDoesNotComeUpLeavesThePackServedAndSentAndSaysWhy() throws Exception {
        Path properties = Files.createDirectories(workspace.resolve("run")).resolve("server.properties");
        Files.writeString(properties, "motd=x\n");
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("server_detach");
        h.core.pack().hostCommand(java(FailingPackHostMain.class));
        pixel("ff0000");
        String built = h.ok("pack");
        String url = built.replaceAll("(?s).*\nurl: (\\S+).*", "$1");
        String why = "could not start a standalone pack host (port " + URI.create(url).getPort()
                + " is taken: Address already in use)";
        assertThat(built).contains("\n" + why + ", so the resource pack is served by this session only, until it ends\n")
                .contains("\napplied: sent to ").doesNotContain("more)");
        assertThat(serves(url)).as("served by this session").isTrue();
        assertThat(PackProperties.url(Platform.PAPER, properties)).as("not offered: it ends with the session").isNull();
        assertThat(h.ok("server_detach")).startsWith("server detached: it keeps running after this session ends.\n"
                + why + ", so the resource pack is served by this session only, until it ends\nWorld tools");
        assertThat(h.ok("msc_workspace", "path", "other", "create", true))
                .contains(" keeps running; " + why + ", so nothing serves the resource pack until the switch back\n");
        h.ok("msc_workspace", "path", workspace.toString());
        assertThat(serves(url)).as("served again after the switch back").isTrue();

        h.core.pack().hostCommand(java(PackHost.class));
        pixel("00ff00");
        String again = h.ok("pack");
        assertThat(again).doesNotContain("could not start").contains("\nserver.properties updated to offer this build");
        String rebuilt = again.replaceAll("(?s).*\nurl: (\\S+).*", "$1");
        assertThat(PackProperties.url(Platform.PAPER, properties)).isEqualTo(rebuilt);
        servedThroughSwitchesAndTheSessionEnd(rebuilt);
    }
}
