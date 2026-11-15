package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bot.Bot;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tools.PlayerTools;
import io.github.imnotstable.msc.core.tools.PlayerToolsAccess;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ownership model on {@link Core}, per resource a workspace owns: what happens to it when the session ends,
 * when msc_workspace switches away, when a switch fails after opening the new folder's parts, and when a call that
 * was already running reaches it after the close began. SessionEndTest covers more interleavings of calls and closes.
 */
class WorkspaceOwnershipTest {
    private static final String CLOSED = "error: the workspace was closed (session ended or msc_workspace), so ";

    @TempDir
    Path workspace;
    private CoreHarness h;
    /** The agent address of the bench a failed switch opened and had to close again. */
    private volatile String halfOpen;

    private static List<String> java(Class<?> main, String... jvmArgs) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(List.of(jvmArgs));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        return command;
    }

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class, "-Dfake.slowCheck=true")));
        h.core.lockWait(Duration.ofMillis(300));
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    private void switchAway() {
        assertThat(h.ok("msc_workspace", "path", "other", "create", true)).startsWith("workspace: ");
    }

    /** A switch to "other" that opens the new folder's parts, then fails; the core reopens the old folder. */
    private void failedSwitch() {
        h.core.openCheck((config, hub) -> {
            if (config.workspace().endsWith("other")) {
                halfOpen = hub.address();
                throw new IOException("refused by the test");
            }
        });
        assertThat(h.error("msc_workspace", "path", "other", "create", true))
                .startsWith("error: could not open ").contains("; still in " + workspace);
        assertThat(agentCanConnect(halfOpen)).as("the half-opened agent socket was closed").isFalse();
        assertThat(h.core.config().workspace()).isEqualTo(workspace);
    }

    /**
     * Calls the current bench's {@code tool} the way a call does that passed every session check before the close
     * began and then missed the interrupt, and closes the core meanwhile. Returns what the call answered.
     */
    private String afterCloseBegan(String tool, Object... pairs) throws Exception {
        Tool.Handler handler = h.core.benchHandler(tool);
        Args args = new Args(CoreHarness.args(pairs));
        CountDownLatch release = new CountDownLatch(1);
        h.core.addBenchTool(new Tool("late", "test", Schema.of().build(), a -> {
            while (true) {
                try {
                    release.await();
                    return handler.call(args);
                } catch (InterruptedException e) {
                    // Missed, as a step that swallows the interrupt does (stopLeftoverHost, #282).
                }
            }
        }));
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> result.complete(h.call("late").text()));
        Thread.sleep(300);
        h.close();
        release.countDown();
        return result.get(30, TimeUnit.SECONDS);
    }

    /**
     * True when an agent can connect at {@code address} with this session's token and stays connected. A handshake,
     * not a bare connect, since a freed port may already belong to another process on this machine; and staying,
     * since CoreServer may still finish a handshake that raced its close, which the hub then drops.
     */
    private boolean agentCanConnect(String address) {
        try {
            var connection = h.agent.connect(address, h.core.hub().token()).connection();
            try {
                Thread.sleep(300);
                return connection.isOpen();
            } finally {
                connection.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** True when {@code url} serves the pack (a 200), the same reasoning as {@link #agentCanConnect}. */
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

    @Nested
    class ServerProcess {
        private ServerManager started() {
            h.ok("server_start", "timeoutSeconds", 30);
            ServerManager server = h.core.server();
            assertThat(server.running()).isTrue();
            return server;
        }

        @Test
        void close() throws Exception {
            ServerManager server = started();
            h.close();
            assertThat(server.running()).isFalse();
        }

        @Test
        void switchStopsIt() {
            ServerManager server = started();
            switchAway();
            assertThat(server.running()).isFalse();
        }

        @Test
        void failedSwitchStopsItAndTheReopenedFolderStartsAgain() {
            ServerManager server = started();
            failedSwitch();
            assertThat(server.running()).isFalse();
            assertThat(h.ok("server_start", "timeoutSeconds", 30)).startsWith("server ready");
        }

        /** #282: the start was reached after the bench closed; nothing would ever stop that server. */
        @Test
        void aStartAfterCloseBeganLaunchesNothing() throws Exception {
            ServerManager server = h.core.server();
            assertThat(afterCloseBegan("server_start", "timeoutSeconds", 30))
                    .isEqualTo(CLOSED + "the server did not start");
            assertThat(server.running()).isFalse();
            assertThat(workspace.resolve(".msc/server.pid")).doesNotExist();
        }
    }

    @Nested
    class AgentConnection {
        @Test
        void close() throws Exception {
            h.withAgent();
            AgentHub hub = h.core.hub();
            h.close();
            assertThat(hub.current()).isNull();
            assertThat(agentCanConnect(hub.address())).isFalse();
        }

        @Test
        void switchClosesIt() throws Exception {
            h.withAgent();
            AgentHub hub = h.core.hub();
            switchAway();
            assertThat(hub.current()).isNull();
            assertThat(agentCanConnect(hub.address())).isFalse();
            assertThat(agentCanConnect(h.core.hub().address())).isTrue();
        }

        @Test
        void failedSwitchClosesItAndTheHalfOpenedOne() throws Exception {
            h.withAgent();
            AgentHub hub = h.core.hub();
            failedSwitch();
            assertThat(agentCanConnect(hub.address())).isFalse();
            assertThat(agentCanConnect(h.core.hub().address())).isTrue();
        }

        @Test
        void aCallAfterCloseBeganFailsAtOnceInsteadOfWaitingForAnAgent() throws Exception {
            AgentHub hub = h.core.hub();
            hub.agentExpected(() -> true);
            hub.connectWait(Duration.ofSeconds(30));
            h.close();
            long started = System.nanoTime();
            assertThatThrownBy(hub::require).hasMessage(CLOSED.substring(7) + "no server agent can connect any more");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Nested
    class BackgroundBuilds {
        private ProcessHandle warmup(String dir) throws Exception {
            assertThat(h.ok("project_scaffold", "name", "Mod", "dir", dir, "template", "forge-mod"))
                    .contains("first build started in the background");
            Path started = workspace.resolve(dir).resolve("check-started");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(started)) {
                assertThat(System.nanoTime()).as("the build started").isLessThan(deadline);
                Thread.sleep(20);
            }
            return ProcessHandle.of(Long.parseLong(Files.readString(started).strip())).orElseThrow();
        }

        private void assertEnded(ProcessHandle build) throws Exception {
            build.onExit().get(3, TimeUnit.SECONDS);
        }

        @Test
        void close() throws Exception {
            ProcessHandle build = warmup("mod");
            h.close();
            assertEnded(build);
        }

        @Test
        void switchStopsIt() throws Exception {
            ProcessHandle build = warmup("mod");
            switchAway();
            assertEnded(build);
        }

        @Test
        void failedSwitchStopsIt() throws Exception {
            ProcessHandle build = warmup("mod");
            failedSwitch();
            assertEnded(build);
        }

        @Test
        void aWarmupAfterCloseBeganDoesNotStart() throws Exception {
            assertThat(afterCloseBegan("project_scaffold", "name", "Mod", "dir", "mod", "template", "forge-mod"))
                    // The files were written before the close: the error says so, and that dev_cycle builds it.
                    .startsWith(CLOSED + "msc-build-warmup did not start\n")
                    .contains("done: write the project files").contains("dev_cycle runs the first build");
            Thread.sleep(1000);
            assertThat(workspace.resolve("mod/check-started")).doesNotExist();
        }
    }

    @Nested
    class PackHost {
        /** Builds a one-texture pack; returns its URL. */
        private String served() {
            h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
            String url = h.ok("pack", "apply", false).lines().filter(l -> l.startsWith("url: ")).findFirst()
                    .orElseThrow().substring("url: ".length());
            assertThat(serves(url)).isTrue();
            return url;
        }

        @Test
        void close() throws Exception {
            String url = served();
            h.close();
            assertThat(serves(url)).isFalse();
        }

        @Test
        void switchStopsIt() {
            String url = served();
            switchAway();
            assertThat(serves(url)).isFalse();
        }

        @Test
        void failedSwitchStopsIt() {
            String url = served();
            failedSwitch();
            assertThat(serves(url)).isFalse();
        }

        @Test
        void aPackBuiltAfterCloseBeganIsNotServed() throws Exception {
            // The workspace remembers this port, and the same sources give the same URL: a late build would serve it.
            String url = served();
            assertThat(afterCloseBegan("pack", "apply", false)).isEqualTo(CLOSED + "the pack was not built or served");
            assertThat(serves(url)).isFalse();
        }
    }

    /** A joined test player without a Minecraft server. */
    private static final class FakeBot implements Bot {
        volatile boolean closed;

        @Override
        public String name() {
            return "MscTester";
        }

        @Override
        public boolean connected() {
            return !closed;
        }

        @Override
        public String disconnectReason() {
            return null;
        }

        @Override
        public String run(List<String> lines, String world) {
            return "";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Nested
    class TestPlayer {
        private FakeBot joined() {
            FakeBot bot = new FakeBot();
            PlayerToolsAccess.adopt(h.core.players(), bot);
            return bot;
        }

        /** Also while a player_test runs: it holds PlayerTools' monitor for its whole run, and close does not wait. */
        @Test
        void close() throws Exception {
            FakeBot bot = joined();
            PlayerTools players = h.core.players();
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread running = Thread.ofVirtual().start(() -> {
                synchronized (players) {
                    holding.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            holding.await();
            long started = System.nanoTime();
            h.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(bot.closed).isTrue();
            release.countDown();
            running.join();
        }

        @Test
        void switchDisconnectsIt() {
            FakeBot bot = joined();
            switchAway();
            assertThat(bot.closed).isTrue();
            assertThat(PlayerToolsAccess.joined(h.core.players())).isFalse();
        }

        @Test
        void failedSwitchDisconnectsIt() {
            FakeBot bot = joined();
            failedSwitch();
            assertThat(bot.closed).isTrue();
            assertThat(PlayerToolsAccess.joined(h.core.players())).isFalse();
        }

        @Test
        void aPlayerAfterCloseBeganDoesNotStay() throws Exception {
            PlayerTools players = h.core.players();
            assertThat(afterCloseBegan("player_test", "actions", List.of("jump")))
                    .isEqualTo(CLOSED + "player_test did not run");
            // One that was joining when the close came leaves at once.
            FakeBot late = new FakeBot();
            assertThatThrownBy(() -> PlayerToolsAccess.adopt(players, late))
                    .hasMessage(CLOSED.substring(7) + "the test player left again");
            assertThat(late.closed).isTrue();
        }
    }

    /** Tool call threads belong to the core, not a workspace; SessionEndTest covers close and calls after it. */
    @Nested
    class ToolCalls {
        @Test
        void callsAfterAFailedSwitchRunOnTheReopenedFolder() {
            failedSwitch();
            assertThat(h.ok("msc_workspace")).isEqualTo("workspace: " + workspace);
            assertThat(h.ok("msc_status")).startsWith("msc-core ");
        }
    }

    /**
     * #721: with a fixed agent port (MSC_CORE_ADDRESS, as msc-bench sets it) every switch binds the port the old
     * workspace just let go, and a failed one says what its close stopped and never leaves the session stuck.
     */
    @Nested
    class FixedAgentPort {
        private int port;

        @BeforeEach
        void fixedPort() throws Exception {
            h.close();
            try (ServerSocket free = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                port = free.getLocalPort();
            }
            h = new CoreHarness(workspace, c -> c.withAgentPort(port).withServerCommand(java(FakeServerMain.class)));
            h.core.lockWait(Duration.ofMillis(300));
        }

        private void agentConnects() {
            assertThat(h.core.hub().address()).endsWith(":" + port);
            assertThat(agentCanConnect(h.core.hub().address())).isTrue();
        }

        @Test
        void switchesBackAndForth() throws Exception {
            Path other = Files.createDirectories(workspace.resolve("other"));
            for (int i = 0; i < 20; i++) {
                Path target = i % 2 == 0 ? other : workspace;
                assertThat(h.ok("msc_workspace", "path", target.toString()))
                        .startsWith("workspace: " + target.toRealPath());
            }
            assertThat(h.ok("msc_workspace", "path", workspace.toString())).startsWith("workspace is already ");
            agentConnects();
        }

        @Test
        void aFailedSwitchSaysWhatItsCloseStoppedAndKeepsThePort() {
            h.ok("server_start", "timeoutSeconds", 30);
            PlayerToolsAccess.adopt(h.core.players(), new FakeBot());
            h.core.openCheck((config, hub) -> {
                if (config.workspace().endsWith("other")) {
                    throw new IOException("refused by the test");
                }
            });
            assertThat(h.error("msc_workspace", "path", "other", "create", true)).isEqualTo("error: could not open "
                    + workspace.resolve("other") + " (refused by the test); still in " + workspace
                    + "\nstopped the dev server of " + workspace + "; the test player left");
            agentConnects();
            assertThat(h.ok("server_start", "timeoutSeconds", 30)).startsWith("server ready");
        }

        @Test
        void aSwitchThatCannotReopenTheOldFolderLeavesOneTheNextSwitchOpens() throws Exception {
            List<Path> opens = new CopyOnWriteArrayList<>();
            h.core.openCheck((config, hub) -> {
                opens.add(config.workspace());
                throw new IOException("refused by the test");
            });
            assertThat(h.error("msc_workspace", "path", "other", "create", true))
                    .contains(", nor reopen " + workspace + " (refused by the test), so no workspace is open")
                    .endsWith("\nmsc_workspace path=" + workspace + " opens it again");
            assertThat(h.error("server_start")).startsWith(CLOSED);
            // #727: every status says no workspace is open, and how to open it again.
            String closed = "none open (" + workspace + " was closed; msc_workspace path=" + workspace
                    + " opens it again)";
            assertThat(h.ok("msc_workspace")).isEqualTo("workspace: " + closed);
            assertThat(h.ok("msc_status")).contains("\nworkspace: " + closed + "\n");
            // Following the hint while the folder still cannot open tries it once and names it once.
            opens.clear();
            assertThat(h.error("msc_workspace", "path", workspace.toString())).isEqualTo("error: could not open "
                    + workspace + " (refused by the test), so no workspace is open\nmsc_workspace path=" + workspace
                    + " opens it again");
            assertThat(opens).containsExactly(workspace);
            h.core.openCheck((config, hub) -> {
            });
            assertThat(h.ok("msc_workspace", "path", workspace.toString())).startsWith("workspace: ");
            agentConnects();
            assertThat(h.ok("server_start", "timeoutSeconds", 30)).startsWith("server ready");
        }
    }

    /** Temp folders belong to the call that makes them, which deletes them also when it fails. */
    @Nested
    class TempFolders {
        @Test
        void aRefusedScaffoldLeavesNoStagingFolder() throws Exception {
            Files.createDirectories(workspace.resolve(".msc"));
            assertThat(h.error("project_scaffold", "name", "Minecraft", "template", "forge-mod"))
                    .contains("which the forge loader keeps for itself");
            try (Stream<Path> data = Files.list(workspace.resolve(".msc"))) {
                assertThat(data.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("scaffold-"));
            }
        }
    }
}
