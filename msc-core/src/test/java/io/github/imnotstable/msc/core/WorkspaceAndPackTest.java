package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
