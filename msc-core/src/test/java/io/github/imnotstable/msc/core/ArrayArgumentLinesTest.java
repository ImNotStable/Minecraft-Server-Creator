package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.bot.Bot;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tools.PlayerToolsAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #657: a line break in an array argument splits it into one item per line, for every array argument of every tool,
 * so {@code actions="say hi\nwait 20"} runs two actions, not one that chats "hi wait 20".
 */
class ArrayArgumentLinesTest {
    /** Every array argument of every tool, each with a test below. */
    private static final Set<String> COVERED = Set.of("player_test actions", "texture palette", "texture rows",
            "model_create textures", "plugins install");

    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace).withAgent();
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    @Test
    void everyArrayArgumentIsCoveredHere() {
        Set<String> arrays = new TreeSet<>();
        for (Tool tool : h.core.registry().tools()) {
            JsonObject properties = tool.inputSchema().getAsJsonObject("properties");
            for (String name : properties.keySet()) {
                if (properties.getAsJsonObject(name).get("type").getAsString().equals("array")) {
                    arrays.add(tool.name() + " " + name);
                }
            }
        }
        assertThat(arrays).containsExactlyInAnyOrderElementsOf(COVERED);
    }

    @Test
    void playerTestRunsOneActionPerLine() {
        RecordingBot bot = new RecordingBot();
        PlayerToolsAccess.adopt(h.core.players(), bot);
        h.ok("player_test", "actions", "say hi\nwait 20");
        h.ok("player_test", "actions", List.of("say hi\r\nwait 20\n", "jump"));
        assertThat(bot.runs).containsExactly(List.of("say hi", "wait 20"), List.of("say hi", "wait 20", "jump"));
        assertThat(h.error("player_test", "actions", "say hi\twait 20")).isEqualTo("error: argument actions has a"
                + " control character (U+0009); only line breaks may be in it, one item per line");
        assertThat(h.error("player_test", "actions", "\n\n")).isEqualTo("error: actions is empty");
    }

    @Test
    void pluginsInstallTakesOneIdPerLine() {
        // Ids are checked before any download, so the second line's bad source is what the call names.
        assertThat(h.error("plugins", "install", "spigot:1\ncurseforge:x"))
                .contains("\"curseforge:x\": the source must be modrinth, hangar or spigot");
    }

    @Test
    void textureTakesPaletteAndRowsOneEntryPerLine() {
        h.ok("texture", "name", "item/lines", "palette", "r=#ff0000\nb=#0000ff", "rows", "rb\nbr\n");
        h.ok("texture", "name", "item/items", "palette", List.of("r=#ff0000", "b=#0000ff"), "rows", List.of("rb", "br"));
        assertThat(h.ok("texture_view", "name", "item/lines")).isEqualTo(h.ok("texture_view", "name", "item/items"))
                .endsWith("\nab\nba");
    }

    @Test
    void modelCreateTakesTexturesOneSlotPerLine() throws Exception {
        h.ok("texture", "name", "block/a", "kind", "noise", "color", "#808080");
        h.ok("texture", "name", "block/b", "kind", "noise", "color", "#404040");
        h.ok("model_create", "name", "block/lines", "kind", "cube_column", "textures", "side=block/a\nend=block/b",
                "item", false);
        h.ok("model_create", "name", "block/items", "kind", "cube_column", "textures",
                List.of("side=block/a", "end=block/b"), "item", false);
        Path models = workspace.resolve(".msc/pack/assets/msc/models/block");
        assertThat(Files.readString(models.resolve("lines.json"))).isEqualTo(Files.readString(models.resolve("items.json")))
                .contains("msc:block/a", "msc:block/b");
    }

    /** A joined test player without a Minecraft server, which keeps the actions it was given. */
    private static final class RecordingBot implements Bot {
        final List<List<String>> runs = new ArrayList<>();

        @Override
        public String name() {
            return "MscTester";
        }

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public String disconnectReason() {
            return null;
        }

        @Override
        public String run(List<String> lines, String world) {
            runs.add(List.copyOf(lines));
            return "ran " + lines.size();
        }

        @Override
        public void close() {
        }
    }
}
