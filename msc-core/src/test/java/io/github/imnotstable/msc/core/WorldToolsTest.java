package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldToolsTest {
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
    void worldToolsFailAtOnceWhenNoServerIsRunning() throws Exception {
        try (CoreHarness idle = new CoreHarness(workspace.resolve("idle"))) {
            idle.core.hub().connectWait(java.time.Duration.ofSeconds(30));
            long start = System.nanoTime();
            assertThat(idle.error("world_summary", "region", "0,0,0 1,1,1")).contains("server is not running")
                    .contains("server_start");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
        }
    }

    @Test
    void renderReturnsAPngWithACaptionAndLogsItsTokens() throws Exception {
        Path ws = workspace.resolve("logged");
        try (CoreHarness r = new CoreHarness(ws, c -> c.withToolLog(ws.resolve("calls.jsonl"))).withAgent()) {
            r.ok("build_batch", "steps", "fill 0,64,0 3,66,3 oak_planks");
            ToolRegistry.Result result = r.call("world_render", "region", "0,62,0 5,67,5", "view", "top", "size", 128);
            assertThat(result.ok()).isTrue();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.png()));
            assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(128);
            assertThat(result.text()).startsWith("Top view").contains("oak_planks").contains("\nsaved: ")
                    .contains("\nMarkers, numbered (green dot = creature): 1 cow at 1.5,64,1.5. Behind blocks (hollow or faded): 1.\n");
            assertThat(r.ok("world_render", "region", "0,62,0 5,67,5", "view", "top", "size", 64, "entities", false))
                    .doesNotContain("Markers");
            Path saved = Path.of(result.text().substring(result.text().indexOf("saved: ") + 7));
            assertThat(saved).hasBinaryContent(result.png()).startsWith(ws.resolve(".msc/renders"));
            assertThat(Files.readString(ws.resolve("calls.jsonl"))).containsPattern("\"tool\":\"world_render\".*\"imageTokens\":\\d+");
        }
        assertThat(h.error("world_render", "region", "0,0,0 1,1,1", "view", "sideways")).startsWith("error: ").doesNotContain("\n");
        assertThat(h.error("world_render", "region", "0,0,0 1,1,1", "size", 10)).contains("64");
        assertThat(h.agent.reads).isEmpty();
    }

    @Test
    void renderLabelsSignsWithTheirText() {
        h.ok("build_batch", "steps", "set 2,64,2 oak_sign");

        assertThat(h.ok("world_render", "region", "0,62,0 5,67,5", "view", "top", "size", 64))
                .contains("sign \"Welcome\" at 2.5,64.5,2.5");
        assertThat(h.ok("world_render", "region", "0,62,0 5,67,5", "view", "top", "size", 64, "entities", false))
                .doesNotContain("Welcome");
    }

    @Test
    void renderStitchesLargeRegionsAndRefusesHugeOnes() {
        ToolRegistry.Result result = h.call("world_render", "region", "0,0,0 129,63,127", "size", 64);
        assertThat(result.ok()).as(result.text()).isTrue();
        assertThat(h.agent.reads).hasSize(2);
        assertThat(result.png()).isNotEmpty();
        assertThat(h.error("world_render", "region", "0,0,0 999,63,999"))
                .isEqualTo("error: region has 64000000 blocks; world_render takes at most 16000000");
        assertThat(h.agent.reads).hasSize(2);
    }

    @Test
    void fillThenSliceShowsLayersWithLegend() {
        assertThat(h.ok("build_batch", "steps", "fill 0,64,0 3,64,2 oak_planks"))
                .isEqualTo("edit e1: 12 blocks changed, region world 0,64,0 3,64,2");
        h.ok("build_batch", "steps", "set 1,65,1 glass\nset 2,65,1 oak_stairs[facing=north]");
        String slice = h.ok("world_slice", "region", "0,63,0 3,66,2");
        assertThat(slice).isEqualTo("""
                region world 0,63,0 3,66,2 size 4x4x3 rows=z cols=x
                legend: .=air #=stone a=oak_planks b=glass c=oak_stairs
                y=63 all #
                y=64 all a
                y=65
                ....
                .bc.
                ....
                y=66 all .""");
        assertThat(h.ok("world_slice", "region", "2,65,1 2,65,1", "states", true))
                .contains("#=oak_stairs[facing=north]");
    }

    @Test
    void hollowFillMakesARoomInOneEdit() {
        h.ok("build_batch", "steps", "fill 0,64,0 2,66,2 glass");
        assertThat(h.ok("build_batch", "steps", "fill 0,64,0 2,66,2 oak_planks hollow"))
                .isEqualTo("edit e2: 27 blocks changed, region world 0,64,0 2,66,2\nblocks: oak_planks 26, air 1");
        assertThat(h.ok("world_slice", "region", "0,65,0 2,65,2")).contains("""
                y=65
                ###
                #.#
                ###""");
        assertThat(h.ok("build_undo")).contains("undid e2");
        assertThat(h.ok("world_summary", "region", "0,64,0 2,66,2")).contains("glass 27");
        assertThat(h.error("build_batch", "steps", "fill 0,64,0 2,66,2 stone cube"))
                .contains("did not understand \"cube\"");
    }

    @Test
    void sliceCanBePastedBackWithBuildStructure() {
        h.ok("build_batch", "steps", """
                structure 10,64,10
                legend: #=stone_bricks g=glass
                y=0
                ###
                #.#
                ###
                y=1
                #g#
                g_g
                #g#
                y=2 same as y=0
                y=3 all #
                end
                """);
        assertThat(h.agent.get(11, 64, 11)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(11, 65, 10)).isEqualTo("minecraft:glass");
        assertThat(h.agent.get(11, 66, 11)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(11, 67, 11)).isEqualTo("minecraft:stone_bricks");
        String slice = h.ok("world_slice", "region", "10,64,10 12,67,12");
        String body = slice.substring(slice.indexOf("legend:"));
        h.ok("build_batch", "steps", "structure 20,64,20\n" + body + "\nend");
        String copy = h.ok("world_slice", "region", "20,64,20 22,67,22");
        assertThat(copy.substring(copy.indexOf("legend:"))).isEqualTo(body);
    }

    @Test
    void structureErrorsSayWhatToFix() {
        assertThat(h.error("build_batch", "steps", "structure 0,64,0\ny=0\n#x\nend"))
                .contains("symbol '#'").contains("not in the legend");
        assertThat(h.error("build_batch", "steps", "structure 0,64,0\nlegend: #=stone\ny=0\n##\n#\nend"))
                .contains("row 2 has 1 columns, expected 2");
        assertThat(h.error("world_summary", "region", "0,64,0 3,64")).contains("x1,y1,z1 x2,y2,z2");
    }

    @Test
    void undoRestoresTheLastEditAndForgetsIt() {
        h.ok("build_batch", "steps", "fill 0,64,0 1,64,1 gold_block");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:gold_block");
        assertThat(h.ok("build_undo")).isEqualTo("undid e1: 4 blocks restored");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.error("build_undo")).contains("nothing to undo");
    }

    @Test
    void largeFillIsSplitAndUndoneAsOneEdit() {
        // 200 x 1 x 200: two 128-wide pieces per axis, so four agent calls behind one edit id.
        assertThat(h.ok("build_batch", "steps", "fill 0,70,0 199,70,199 glass"))
                .isEqualTo("edit e1: 40000 blocks changed, region world 0,70,0 199,70,199");
        // Volume fits one call; widen past it to force a split.
        assertThat(h.ok("build_batch", "steps", "fill 0,71,0 199,140,199 glass replace=air"))
                .isEqualTo("edit e2: 2800000 blocks changed, region world 0,71,0 199,140,199");
        assertThat(h.ok("build_undo", "edit", "e2")).isEqualTo("undid e2: 2800000 blocks restored");
        assertThat(h.agent.get(150, 100, 150)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(150, 70, 150)).isEqualTo("minecraft:glass");
    }

    @Test
    void copyMovesBlocksAndCanSkipAir() {
        h.ok("build_batch", "steps", "set 0,64,0 diamond_block\nfill 5,64,5 6,64,5 dirt");
        assertThat(h.ok("build_batch", "steps", "copy 0,64,0 1,64,0 to=5,64,5 air=false"))
                .isEqualTo("edit e2: 1 blocks changed, region world 5,64,5 5,64,5");
        assertThat(h.agent.get(5, 64, 5)).isEqualTo("minecraft:diamond_block");
        assertThat(h.agent.get(6, 64, 5)).isEqualTo("minecraft:dirt");
        // The copy reads the world before the call, turns and repeats like a structure.
        h.ok("build_batch", "steps", "copy 5,64,5 6,64,5 to=20,64,0 rotate=90 repeat=2 step=0,1,0");
        assertThat(h.agent.get(20, 64, 0)).isEqualTo("minecraft:diamond_block");
        assertThat(h.agent.get(20, 64, 1)).isEqualTo("minecraft:dirt");
        assertThat(h.agent.get(20, 65, 1)).isEqualTo("minecraft:dirt");
        assertThat(h.error("build_batch", "steps", "copy 0,64,0 1,64,0")).contains("copy needs to=X,Y,Z");
    }

    @Test
    void summaryCountsBlocksAndLayers() {
        h.ok("build_batch", "steps", "fill 0,64,0 3,64,3 oak_planks\nset 1,66,1 glass");
        assertThat(h.ok("world_summary", "region", "0,62,0 3,67,3")).isEqualTo("""
                region world 0,62,0 3,67,3 size 4x6x4 = 96 blocks, 49% air
                blocks: stone 32, oak_planks 16, glass 1
                non-air bounds: 0,62,0 3,66,3
                non-air per layer (y:count): 62..64:16 65:0 66:1 67:0
                entities: 1 cow""");
    }

    @Test
    void heightmapIsCompact() {
        h.ok("build_batch", "steps", "fill 1,64,1 2,66,2 stone");
        assertThat(h.ok("world_heightmap", "area", "0,0 3,3")).isEqualTo("""
                heightmap world x 0..3 z 0..3 min 63 max 66 mean 64 rows=z cols=x
                each char = height - 63 (0-9 then a-z)
                0000
                0330
                0330
                0000""");
        assertThat(h.ok("world_heightmap", "area", "10,10 12,12")).endsWith("flat at y=63");
    }

    @Test
    void wideHeightmapsUseRunsAndRepeatedRows() {
        h.ok("build_batch", "steps", "fill 2,100,1 3,100,2 stone");
        assertThat(h.ok("world_heightmap", "area", "0,0 5,4")).isEqualTo("""
                heightmap world x 0..5 z 0..4 min 63 max 100 mean 68 rows=z cols=x
                each row: heights by column, v*n = n columns at v, (xN) = row repeated N times
                63*6
                63*2 100*2 63*2 (x2)
                63*6 (x2)""");
    }

    @Test
    void resultsAreCutToTheBudgetWithAHint() {
        h.ok("build_batch", "steps", "structure 0,64,0\nlegend: a=stone b=dirt\ny=0\n"
                + "ab".repeat(16).concat("\n").repeat(16) + "y=1\n" + "ba".repeat(16).concat("\n").repeat(16) + "end");
        String slice = h.ok("world_slice", "region", "0,64,0 31,65,15", "maxChars", 400);
        assertThat(slice.length()).isLessThan(520);
        assertThat(slice).contains("[truncated:").contains("smaller area");
    }

    @Test
    void schematicRoundTrip() throws Exception {
        h.ok("build_batch", "steps", "structure 0,64,0\nlegend: #=stone_bricks s=oak_stairs[facing=east]\ny=0\n#s\n.#\nend");
        assertThat(h.ok("structure_save", "region", "0,64,0 1,64,1", "file", "out/tiny.schem")).startsWith("saved 2x1x2 to ");
        assertThat(Files.size(workspace.resolve("out/tiny.schem"))).isPositive();
        h.ok("build_batch", "steps", "structure 8,80,8 file=out/tiny.schem");
        assertThat(h.agent.get(8, 80, 8)).isEqualTo("minecraft:stone_bricks");
        assertThat(h.agent.get(9, 80, 8)).isEqualTo("minecraft:oak_stairs[facing=east]");
        assertThat(h.agent.get(9, 80, 9)).isEqualTo("minecraft:stone_bricks");
        assertThat(h.error("build_batch", "steps", "structure 0,0,0 file=missing.schem")).contains("no file");
    }

    @Test
    void worldToolsExplainAMissingAgent() throws Exception {
        try (CoreHarness alone = new CoreHarness(workspace.resolve("other"))) {
            alone.core.hub().agentExpected(() -> true); // as if a server were running without the agent
            assertThat(alone.error("world_summary", "region", "0,0,0 1,1,1"))
                    .contains("no server agent connected").contains("server_start");
        }
    }

    @Test
    void entitiesListsNameHealthAndTagsAndFiltersByType() throws Exception {
        h.agent.entities.add(new Ops.EntityInfo("minecraft:zombie", "00000000-0000-0000-0000-000000000001",
                2.5, 64, 3.25, "Brute", List.of("boss", "wave"), 7.0, 20.0));
        h.agent.entities.add(new Ops.EntityInfo("minecraft:text_display", "00000000-0000-0000-0000-000000000002",
                0.5, 66, 0.5, "Wave 3", List.of(), null, null));
        h.agent.entities.add(new Ops.EntityInfo("minecraft:zombie", "00000000-0000-0000-0000-000000000003",
                90, 64, 90, null, List.of(), 20.0, 20.0));

        assertThat(h.ok("world_entities", "region", "0,60,0 10,70,10")).isEqualTo("""
                2 entities: 1 text_display 1 zombie
                zombie "Brute" 2.5,64,3.3 hp 7/20 tags boss,wave
                text_display "Wave 3" 0.5,66,0.5""");
        assertThat(h.ok("world_entities", "region", "0,60,0 100,70,100", "type", "zombie", "limit", 1, "ids", true))
                .isEqualTo("2 entities, first 1 shown\nzombie \"Brute\" 2.5,64,3.3 hp 7/20 tags boss,wave"
                        + " id 00000000-0000-0000-0000-000000000001");
        assertThat(h.ok("world_entities", "region", "0,60,0 1,61,1", "type", "pig"))
                .startsWith("no pig entities in the region").endsWith("\"forceload add 0 0 1 1\" loads them, then ask again");
        assertThat(h.error("world_entities", "region", "0,0,0 5000,1,1")).contains("2048");
    }
}
