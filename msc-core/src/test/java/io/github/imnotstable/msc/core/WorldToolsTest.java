package io.github.imnotstable.msc.core;

import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
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
        h.ok("build_batch", "steps", "set 2,64,2 oak_sign[rotation=0]");

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
        h.ok("build_batch", "steps", "set 1,65,1 glass\nset 2,65,1 oak_stairs[facing=north,half=bottom]");
        String slice = h.ok("world_slice", "region", "0,63,0 3,66,2");
        assertThat(slice).isEqualTo("""
                region world 0,63,0 3,66,2 size 4x4x3 rows=z cols=x
                legend: .=air #=stone a=oak_planks b=glass@1,65,1 c=oak_stairs@2,65,1
                y=63 all #
                y=64 all a
                y=65
                ....
                .bc.
                ....
                y=66 all .""");
        assertThat(h.ok("world_slice", "region", "2,65,1 2,65,1", "states", true))
                .contains("#=oak_stairs[facing=north,half=bottom]");
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
    void sliceWritesRepeatedRowsAndLayerRunsOnceAndPastesBack() {
        h.ok("build_batch", "steps", "fill 0,64,0 6,67,6 stone_bricks walls\nfill 0,68,0 6,68,6 oak_planks\n"
                + "set 3,64,0 oak_stairs[facing=north,half=bottom]\nset 0,66,3 glass");
        String slice = h.ok("world_slice", "region", "0,64,0 6,70,6");
        assertThat(slice).isEqualTo("""
                region world 0,64,0 6,70,6 size 7x7x7 rows=z cols=x
                legend: .=air #=stone_bricks a=oak_planks b=oak_stairs@3,64,0 c=glass@0,66,3
                y=64
                ###b###
                #.....# (x5)
                #######
                y=65
                #######
                #.....# (x5)
                #######
                y=66
                #######
                #.....# (x2)
                c.....#
                #.....# (x2)
                #######
                y=67 same as y=65
                y=68 all a
                y=69..70 all .""");
        String body = slice.substring(slice.indexOf("legend:"));
        // A slice without states leaves the stair's placement unsaid: build_batch asks for it (ADR 0077).
        assertThat(h.error("build_batch", "steps", "structure 20,64,20\n" + body + "\nend"))
                .contains("oak_stairs needs facing=").contains("states=true");
        String sayStair = body.replace("b=oak_stairs@3,64,0", "b=oak_stairs[facing=north,half=bottom]");
        h.ok("build_batch", "steps", "structure 20,64,20\n" + sayStair + "\nend");
        String copy = h.ok("world_slice", "region", "20,64,20 26,70,26");
        assertThat(copy.substring(copy.indexOf("legend:")))
                .isEqualTo(body.replace("@3,64,0", "@23,64,20").replace("@0,66,3", "@20,66,23"));
        assertThat(h.error("build_batch", "steps", "structure 0,64,0\nlegend: #=stone\ny=0..1\n##\nend"))
                .contains("\"same as\" or \"all\"");
    }

    @Test
    void summaryListsWhereRareBlocksAre() {
        h.ok("build_batch", "steps", "set 5,70,5 gold_block\nset 1,71,2 gold_ore\nset 2,71,2 gold_ore\n"
                + "fill 0,72,0 3,72,0 dirt");
        assertThat(h.ok("world_summary", "region", "0,70,0 7,72,7"))
                .contains("blocks: dirt 4, gold_ore 2 at 1,71,2 2,71,2, gold_block 1 at 5,70,5\n");
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

    /** #695: an older edit is undone only when no later edit still applied set any of its blocks. */
    @Test
    void anOlderEditUnderALaterOneIsNotUndone() {
        h.ok("build_batch", "steps", "fill 0,64,0 2,64,2 stone");
        h.ok("build_batch", "steps", "set 1,64,1 gold_block");
        h.ok("build_batch", "steps", "set 9,64,9 glass");
        assertThat(h.error("build_undo", "edit", "e1")).isEqualTo("error: e1 not undone: later edit e2 set some of "
                + "the same blocks, which undoing e1 would wipe. Undo it first (build_undo edit=e2), then e1.");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:stone");
        assertThat(h.agent.get(1, 64, 1)).isEqualTo("minecraft:gold_block");
        // An older edit no later one touches is undone, and the latest stays the default.
        assertThat(h.ok("build_undo", "edit", "e2")).isEqualTo("undid e2: 1 blocks restored");
        assertThat(h.ok("build_undo", "edit", "e1")).isEqualTo("undid e1: 9 blocks restored");
        assertThat(h.agent.get(1, 64, 1)).isEqualTo("minecraft:air");
        assertThat(h.ok("build_undo")).isEqualTo("undid e3: 1 blocks restored");
        assertThat(h.error("build_undo")).contains("nothing to undo");
    }

    @Test
    void largeFillIsSplitAndUndoneAsOneEdit() {
        // 200 x 1 x 200 = 40,000 blocks fits one agent call.
        assertThat(h.ok("build_batch", "steps", "fill 0,70,0 199,70,199 glass"))
                .isEqualTo("edit e1: 40000 blocks changed, region world 0,70,0 199,70,199\n1 build never reviewed (msc_status lists them): the work is not done");
        // 129 x 64 x 128 is one column of blocks over the region limit: the smallest split, two agent calls.
        h.agent.writes.clear();
        assertThat(h.ok("build_batch", "steps", "fill 0,71,0 128,134,127 glass replace=air"))
                .isEqualTo("edit e2: 1056768 blocks changed, region world 0,71,0 128,134,127\n1 build never reviewed (msc_status lists them): the work is not done");
        assertThat(h.agent.writes).contains(Region.of("world", 0, 71, 0, 127, 134, 127),
                Region.of("world", 128, 71, 0, 128, 134, 127));
        assertThat(h.ok("build_undo", "edit", "e2")).isEqualTo("undid e2: 1056768 blocks restored");
        assertThat(h.agent.get(0, 71, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(128, 134, 127)).isEqualTo("minecraft:air");
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
                blocks: stone 32, oak_planks 16, glass 1 at 1,66,1
                non-air bounds: 0,62,0 3,66,3
                non-air per layer (y:count): 62..64:16 65:0 66:1 67:0
                entities: 1 cow""");
    }

    @Test
    void summaryChecksAPathAndADiffWithoutAPlayer() {
        h.ok("build_batch", "steps", "fill 5,64,0 5,65,4 stone");
        String region = "0,63,0 9,67,4";
        assertThat(h.ok("world_summary", "region", region, "path", "0,64,2 9,64,2"))
                .startsWith("not reachable from 0,64,2 to 9,64,2. Closest: 4,64,2\nwall to the east: stone at 5,64,2 (2 high");
        h.ok("structure_save", "region", region, "file", "snap/before.schem");
        h.ok("build_batch", "steps", "set 5,65,2 air");
        assertThat(h.ok("world_summary", "region", region, "path", "0,64,2 9,64,2"))
                .isEqualTo("reachable: 9 moves, 1 up, 1 drop\nroute: 0,64,2 > 4,64,2 > 5,65,2 > 6,64,2 > 9,64,2");
        assertThat(h.ok("world_summary", "region", region, "diff", "snap/before.schem"))
                .isEqualTo("1 block changed since snap/before.schem, in world 5,65,2 5,65,2\nchanges: stone -> air 1\n5,65,2 stone -> air");
        assertThat(h.error("world_summary", "region", region, "diff", "snap/none.schem")).contains("structure_save");
        assertThat(h.error("world_summary", "region", region, "path", "0,64,2")).contains("two positions");
        assertThat(h.error("world_summary", "region", region, "path", "0,64,2 9,64,2", "diff", "a.schem"))
                .contains("give one of path, diff or review");
    }

    /** #632: the note gives the region's own size and what was compared, also for a snapshot smaller than it. */
    @Test
    void summaryDiffSaysWhenTheRegionAndTheSnapshotDifferInSize() {
        h.ok("build_batch", "steps", "fill 0,64,0 2,64,2 stone");
        h.ok("structure_save", "region", "0,63,0 2,65,2", "file", "small.schem");
        assertThat(h.ok("world_summary", "region", "0,63,0 9,67,9", "diff", "small.schem")).isEqualTo(
                "note: small.schem is 3x3x3, the region 10x5x10; compared only where they overlap, 3x3x3 from 0,63,0 "
                        + "to 2,65,2\nno change since small.schem");
        h.ok("structure_save", "region", "0,64,0 5,64,0", "file", "row.schem");
        assertThat(h.ok("world_summary", "region", "0,64,0 2,67,2", "diff", "row.schem")).isEqualTo(
                "note: row.schem is 6x1x1, the region 3x4x3; compared only where they overlap, 3x1x1 from 0,64,0 "
                        + "to 2,64,0\nno change since row.schem");
        assertThat(h.ok("world_summary", "region", "0,64,0 5,64,0", "diff", "row.schem"))
                .isEqualTo("no change since row.schem");
    }

    /**
     * #509: one rule for structure files. structure_save takes only a .schem name (any case); every reader goes by
     * the content, so whatever it saved reads back through world_summary diff= and build_batch file=, a text
     * structure reads under any name, and a file that is neither is refused in one line naming it.
     */
    @Test
    void structureFilesFollowOneRuleForEveryWriterAndReader() throws Exception {
        String region = "0,63,0 2,65,2";
        h.ok("build_batch", "steps", "fill 0,64,0 2,64,2 stone");
        for (String name : List.of("snap", "snap.txt", "snap.schem.txt")) {
            assertThat(h.error("structure_save", "region", region, "file", name))
                    .isEqualTo("error: file must end in .schem (a Sponge schematic), such as structures/house.schem, not "
                            + workspace.resolve(name));
            assertThat(workspace.resolve(name)).doesNotExist();
        }
        for (String name : List.of("snap.SCHEM", "snap.schem", "d/Snap.Schem")) {
            h.ok("structure_save", "region", region, "file", name);
            assertThat(h.ok("world_summary", "region", region, "diff", name)).startsWith("no change since");
            assertThat(h.ok("build_batch", "steps", "structure 10,64,10 file=" + name, "dryRun", true)).contains("27");
        }
        // A schematic renamed, and a text structure under any name, read by their content.
        Files.copy(workspace.resolve("snap.schem"), workspace.resolve("renamed.txt"));
        Files.writeString(workspace.resolve("post"), "legend: #=stone\ny=0\n#\n");
        assertThat(h.ok("world_summary", "region", region, "diff", "renamed.txt")).startsWith("no change since");
        assertThat(h.ok("build_batch", "steps", "structure 10,64,10 file=post", "dryRun", true)).contains("1");
        Files.write(workspace.resolve("junk.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1});
        String neither = "error: " + workspace.resolve("junk.bin") + " is neither a Sponge schematic (as "
                + "structure_save writes) nor a UTF-8 text structure; save a snapshot with structure_save";
        assertThat(h.error("world_summary", "region", region, "diff", "junk.bin")).isEqualTo(neither);
        assertThat(h.error("build_batch", "steps", "structure 10,64,10 file=junk.bin", "dryRun", true))
                .isEqualTo("error: line 1 (structure 10,64,10 file=junk.bin): " + neither.substring("error: ".length()));
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
        String rows = ("ab".repeat(16) + "\n" + "ba".repeat(16) + "\n").repeat(8);
        h.ok("build_batch", "steps", "structure 0,64,0\nlegend: a=stone b=dirt\ny=0\n" + rows + "y=1\n"
                + rows.substring(33) + "ab".repeat(16) + "\nend");
        String slice = h.ok("world_slice", "region", "0,64,0 31,65,15", "maxChars", 400);
        assertThat(slice.length()).isLessThan(520);
        assertThat(slice).contains("[truncated:").contains("Ask for fewer layers or a smaller area. Or raise maxChars (at most 24000).]");
    }

    @Test
    void schematicRoundTrip() throws Exception {
        h.ok("build_batch", "steps", "structure 0,64,0\nlegend: #=stone_bricks s=oak_stairs[facing=east,half=bottom]\ny=0\n#s\n.#\nend");
        assertThat(h.ok("structure_save", "region", "0,64,0 1,64,1", "file", "out/tiny.schem")).startsWith("saved 2x1x2 to ");
        assertThat(Files.size(workspace.resolve("out/tiny.schem"))).isPositive();
        h.ok("build_batch", "steps", "structure 8,80,8 file=out/tiny.schem");
        assertThat(h.agent.get(8, 80, 8)).isEqualTo("minecraft:stone_bricks");
        assertThat(h.agent.get(9, 80, 8)).isEqualTo("minecraft:oak_stairs[facing=east,half=bottom]");
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

    @Test
    @Timeout(10)
    void summaryOfARegionAtTheIntLimitIsRefused() {
        assertThat(h.error("world_summary", "region", "2147483520,0,0 2147483647,100,100"))
                .contains("x=2147483520 is outside the world");
    }

    @Test
    void structureSaveRefusesSidesASchematicCannotHold() {
        assertThat(h.error("structure_save", "region", "0,64,0 65536,64,0", "file", "wall.schem"))
                .contains("65537x1x1").contains("at most 65535");
        assertThat(workspace.resolve("wall.schem")).doesNotExist();
    }
}
