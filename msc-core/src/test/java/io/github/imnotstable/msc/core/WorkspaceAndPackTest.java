package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** msc_workspace, and a resource pack that outlives detach and the session through server.properties. */
class WorkspaceAndPackTest {
    @TempDir
    Path root;
    private Path first;
    private CoreHarness h;

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @BeforeEach
    void start() throws Exception {
        first = Files.createDirectories(root.resolve("first"));
        h = new CoreHarness(first, c -> c.withServerCommand(java(FakeServerMain.class)));
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    private void serverProperties(Path workspace, String text) throws Exception {
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/server.properties"), text);
    }

    private String pixel() {
        return h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
    }

    private String url(String packBuild) {
        return packBuild.lines().filter(l -> l.startsWith("url: ")).findFirst().orElseThrow().substring(5);
    }

    /** #457: a folder msc_workspace cannot create is a one-line refusal naming it, never an internal error. */
    @Test
    void aFolderThatCannotBeCreatedIsRefusedInOneLine() throws Exception {
        Files.writeString(first.resolve("version.txt"), "1.0");
        assertThat(h.error("msc_workspace", "path", "version.txt/sub", "create", true))
                .startsWith("error: folder " + first.resolve("version.txt/sub") + " could not be created: ")
                .doesNotContain("internal").doesNotContain("\n");
        assertThat(h.error("msc_workspace", "path", "a".repeat(300), "create", true))
                .isEqualTo("error: argument path has a name of 300 bytes (" + "a".repeat(24)
                        + "...); file systems hold at most 255. Use a shorter name.");
        assertThat(h.error("msc_workspace", "path", "a\u0000b")).contains("control character (U+0000)");
        assertThat(h.ok("msc_workspace")).isEqualTo("workspace: " + first);
    }

    @Test
    void switchingMovesEveryToolToTheNewFolderAndStopsTheOldServer() throws Exception {
        h.close();
        h = new CoreHarness(first, c -> c.withServerCommand(java(FakeServerMain.class))
                .withToolLog(first.resolve(".msc/tool-calls.jsonl")));
        assertThat(h.ok("msc_workspace")).isEqualTo("workspace: " + first);
        Path second = root.resolve("second project");
        assertThat(h.error("msc_workspace", "path", "../second project")).contains("no folder").contains("create=true");
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(h.ok("msc_workspace", "path", "../second project", "create", true))
                .startsWith("workspace: " + second.toRealPath())
                .contains("stopped the dev server of " + first)
                .endsWith("server folder: " + second.toRealPath().resolve("run") + "; platform: paper");
        assertThat(h.core.config().workspace()).isEqualTo(second.toRealPath());
        assertThat(h.ok("msc_status")).contains("workspace: " + second.toRealPath());
        pixel();
        assertThat(second.resolve(".msc/pack/assets/msc/textures/item/dot.png")).exists();
        assertThat(first.resolve(".msc/pack")).doesNotExist();
        assertThat(h.ok("msc_workspace", "path", second.toString())).startsWith("workspace is already ");
        assertThat(h.ok("msc_status", "stats", true)).contains("msc_workspace")
                .contains("log: " + second.toRealPath().resolve(".msc/tool-calls.jsonl"));
        // The tool log moved along: later calls land in the new workspace's log, not the first one's.
        long firstLines = Files.readAllLines(first.resolve(".msc/tool-calls.jsonl")).size();
        h.ok("msc_status");
        assertThat(Files.readAllLines(first.resolve(".msc/tool-calls.jsonl"))).hasSize((int) firstLines);
        assertThat(second.resolve(".msc/tool-calls.jsonl")).content().contains("\"tool\":\"msc_status\"");
    }

    @Test
    void aLongCallHoldsUpNeitherASwitchNorTheEndOfTheSession() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        h.core.lockWait(Duration.ofMillis(300));
        CompletableFuture<String> wait = CompletableFuture.supplyAsync(
                () -> h.call("server_wait", "until", "^no such line$", "timeoutSeconds", 600).text());
        Thread.sleep(500);
        long started = System.nanoTime();
        assertThat(h.error("msc_workspace", "path", "../second", "create", true))
                .contains("server_wait is still running").contains("still in " + first);
        assertThat(h.ok("msc_status")).contains("workspace: " + first);
        h.close();
        // Closing interrupts the call (or stops the server under it) instead of waiting out its 600 s.
        assertThat(wait.get(10, TimeUnit.SECONDS)).containsAnyOf("server_wait was stopped: the session ended", "the server stopped");
        assertThat(h.core.server().running()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void theWorkspaceKeepsItsPackPortFromSessionToSession() throws Exception {
        pixel();
        String before = url(h.ok("pack", "apply", false));
        h.close();
        h = new CoreHarness(first);
        pixel();
        assertThat(url(h.ok("pack", "apply", false))).isEqualTo(before);
        assertThat(first.resolve(".msc/pack-port")).exists();
    }

    @Test
    void detachWritesThePackIntoServerPropertiesAndTheNextStartServesItAgain() throws Exception {
        serverProperties(first, "motd=Gearfall\nresource-pack=\nresource-pack-sha1=\nmax-players=8\n");
        h.ok("server_start", "timeoutSeconds", 30);
        pixel();
        String url = url(h.ok("pack", "required", true));
        assertThat(h.ok("server_detach")).contains("server.properties now offers the pack too");
        Path props = first.resolve("run/server.properties");
        String sha1 = url.substring(url.indexOf("/pack-") + 6, url.length() - 4);
        assertThat(Files.readAllLines(props)).containsExactly("motd=Gearfall",
                "resource-pack=" + url.replace(":", "\\:"), "resource-pack-sha1=" + sha1, "max-players=8",
                "resource-pack-id=" + java.util.UUID.nameUUIDFromBytes(("msc-pack:" + url).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                "require-resource-pack=true");
        h.close();

        // A new session: the standalone host is stopped and the core serves the same pack on the same port.
        h = new CoreHarness(first, c -> c.withServerCommand(java(FakeServerMain.class)));
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("stopped the pack host left running");
        assertThat(LifecycleToolsTest.fetchCode(url)).isEqualTo(200);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#00ff00"), "rows", List.of("a"));
        String rebuilt = url(h.ok("pack", "apply", false));
        assertThat(rebuilt).isNotEqualTo(url);
        assertThat(Files.readString(props)).contains("resource-pack=" + rebuilt.replace(":", "\\:"));
    }

    /** #655: a pack built while no agent is connected is the workspace's pack, which the next start sends. */
    @Test
    void aPackBuiltBeforeTheStartIsSentByIt() {
        pixel();
        assertThat(h.ok("pack")).contains("\nnot sent yet: no server agent connected; the next server_start or "
                + "dev_cycle sends it\n");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("\nresource pack sent: players who join get it\n");
    }

    /** #655: the pack belongs to the workspace, so a switch away and back neither forgets it nor its build. */
    @Test
    void aSwitchAwayAndBackKeepsTheWorkspacesPack() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        pixel();
        String url = url(h.ok("pack"));
        h.ok("msc_workspace", "path", "../second", "create", true);
        h.ok("msc_workspace", "path", first.toString());
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("\nresource pack sent: players who join get it\n");
        assertThat(h.ok("server_detach")).contains("and so does the resource pack host");
        assertThat(LifecycleToolsTest.fetchCode(url)).isEqualTo(200);
        assertThat(h.ok("server_stop")).isEqualTo("server stopped, and the pack host it kept running");
    }

    /** #655: a build an earlier session left is served on server_detach as it is, never "no pack built yet". */
    @Test
    void detachServesTheBuildAnEarlierSessionLeft() throws Exception {
        pixel();
        String url = url(h.ok("pack", "apply", false));
        h.close();
        h = new CoreHarness(first, c -> c.withServerCommand(java(FakeServerMain.class)));
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).doesNotContain("resource pack");
        assertThat(h.ok("server_detach")).contains("and so does the resource pack host");
        assertThat(LifecycleToolsTest.fetchCode(url)).isEqualTo(200);
        assertThat(h.ok("server_stop")).isEqualTo("server stopped, and the pack host it kept running");
    }

    /** #655: an agent that says its server is ready after the start was reported gets the workspace's pack too. */
    @Test
    void anAgentReadyAfterTheStartGetsThePack() throws Exception {
        pixel();
        String url = url(h.ok("pack"));
        h.core.hub().agentExpected(() -> true);
        AgentClient.Session session = h.agent.connect(h.core.hub().address(), h.core.hub().token());
        try {
            h.core.hub().require();
            session.connection().sendEvent(Events.SERVER_READY, new JsonObject());
            for (int i = 0; i < 100 && sent().isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertThat(sent()).containsExactly(url);
        } finally {
            session.connection().close();
        }
    }

    private List<String> sent() {
        synchronized (h.agent) {
            return h.agent.packs.stream().map(p -> p.url()).toList();
        }
    }

    /** The platforms whose server does not read its pack from server.properties (ADR 0017, #718). */
    private static final Set<Platform> NO_PACK_LINES = EnumSet.of(Platform.MINESTOM, Platform.VELOCITY);

    /**
     * #718: every call that writes the pack into server.properties (pack, server_detach, the next start) writes it only
     * on a platform whose server reads it there, and server_detach promises a restart outside MSC only the pack it
     * will offer. The file is one an earlier core left offering this workspace's pack.
     */
    @ParameterizedTest
    @EnumSource(Platform.class)
    void onlyAServerThatReadsThePackThereGetsItInServerProperties(Platform platform) throws Exception {
        boolean reads = !NO_PACK_LINES.contains(platform);
        assertThat(platform.readsPackProperties()).isEqualTo(reads);
        Platform.remember(h.core.config(), platform);
        h.ok("server_start", "timeoutSeconds", 30);
        pixel();
        String url = url(h.ok("pack", "apply", false));
        String before = "server-port=25613\nresource-pack=" + url.replace(":", "\\:") + "\n";
        serverProperties(first, before);
        Path props = first.resolve("run/server.properties");
        assertThat(h.ok("pack", "apply", false).contains("server.properties updated")).isEqualTo(reads);
        String detached = h.ok("server_detach");
        if (reads) {
            assertThat(detached).contains("\nserver.properties now offers the pack too");
            assertThat(Files.readString(props)).contains("resource-pack-sha1=" + url.substring(url.indexOf("/pack-") + 6,
                    url.length() - 4));
        } else {
            assertThat(detached).doesNotContain("now offers").contains("\n" + platform.displayName()
                    + " does not read the pack from server.properties, so a restart outside MSC does not offer it.");
            assertThat(props).hasContent(before);
        }
        // #722: world tools always refuse on a proxy, and it has no world to save.
        String last = detached.lines().toList().getLast();
        if (platform.proxy()) {
            assertThat(last).startsWith("server_command works until this session ends;").doesNotContain("orld");
            assertThat(h.error("world_summary", "region", "world 0 -64 0 15 0 15"))
                    .contains("is a proxy with no world");
        } else {
            assertThat(last).startsWith("World tools and server_command work until this session ends;")
                    .contains("stops the server (world saved) and the pack host.");
        }
        h.close();
        h = new CoreHarness(first, c -> c.withServerCommand(java(FakeServerMain.class)));
        // #728: the start stops the leftover first, and on a proxy says nothing about a world it does not have.
        String took = h.ok("server_start", "timeoutSeconds", 30).lines().findFirst().orElseThrow();
        assertThat(took).startsWith("stopped a server left running by an earlier session (pid ");
        if (platform.proxy()) {
            assertThat(took).doesNotContain("orld").doesNotContain("save").endsWith(")");
        } else {
            assertThat(took).containsAnyOf("saved over RCON)", "killed: world changes since its last autosave");
        }
        if (!reads) {
            assertThat(props).hasContent(before);
        }
        h.ok("server_stop");
    }

    @Test
    void anEmptiedPackIsNoLongerOffered() throws Exception {
        serverProperties(first, "resource-pack=http\\://127.0.0.1\\:1/pack-" + "a".repeat(40) + ".zip\n"
                + "resource-pack-sha1=" + "a".repeat(40) + "\n");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("server.properties no longer offers the resource pack");
        assertThat(Files.readString(first.resolve("run/server.properties"))).contains("resource-pack=\n");
    }

    @Test
    void aForeignPackUrlIsLeftAlone() throws Exception {
        serverProperties(first, "resource-pack=https\\://example.com/pack.zip\n");
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(Files.readString(first.resolve("run/server.properties")))
                .isEqualTo("resource-pack=https\\://example.com/pack.zip\n");
    }

    @Test
    void handheldTexturesAreHeldLikeTools() throws Exception {
        h.ok("texture", "name", "item/ruby_sword", "model", "handheld", "palette", List.of("a=#ff0000"),
                "rows", List.of("a"));
        assertThat(first.resolve(".msc/pack/assets/msc/models/item/ruby_sword.json")).content()
                .contains("\"parent\":\"minecraft:item/handheld\"");
    }

    @Test
    void theEulaStepGivesTheExactQuestion() {
        assertThat(h.ok("project_scaffold", "name", "Game", "dir", "game"))
                .contains("ask the user exactly: \"To run a Minecraft server you must agree to the Minecraft EULA "
                        + "(https://aka.ms/MinecraftEULA). Do you agree to it? (yes/no)\"")
                .contains("a minestom-server project needs no EULA");
    }
}
