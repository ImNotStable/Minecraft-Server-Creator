package io.github.imnotstable.msc.core;

import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolsTest {
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
    void batchIsOneEditWithOneUndo() {
        String out = h.ok("build_batch", "steps", """
                # a room, a pillar and a door
                fill 0,64,0 4,67,4 stone_bricks hollow
                set 2,65,0 oak_door[half=lower]
                set 2,66,0 oak_door[half=upper]
                cylinder 10,64,10 r=1 h=3 oak_log
                """);
        assertThat(out).startsWith("edit e1: ").contains("region world 0,64,0 11,67,11").contains("stone_bricks 80");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:stone_bricks");
        assertThat(h.agent.get(2, 65, 2)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(2, 65, 0)).isEqualTo("minecraft:oak_door[half=lower]");
        assertThat(h.agent.get(10, 66, 11)).isEqualTo("minecraft:oak_log");
        assertThat(h.ok("build_undo")).startsWith("undid e1");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(2, 65, 0)).isEqualTo("minecraft:air");
    }

    @Test
    void badBlockNamesStopTheWholeBatchAndAreAllListed() {
        String err = h.error("build_batch", "steps", """
                fill 0,64,0 2,64,2 stone
                set 1,65,1 no_such_lamp
                disc 0,70,0 r=2 grass_block:5,no_such_moss:1
                """);
        assertThat(err).contains("nothing placed").contains("2 blocks are not valid")
                .contains("unknown block no_such_lamp").contains("unknown block no_such_moss");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
    }

    @Test
    void dryRunPlacesNothing() {
        String out = h.ok("build_batch", "steps", "disc 0,63,0 r=2 grass_block", "dryRun", true);
        assertThat(out).isEqualTo("dry run, nothing placed: 21 blocks in world -2,63,-2 2,63,2 size 5x1x5\n"
                + "blocks: grass_block 21\nblock names: all valid");
        assertThat(h.agent.get(0, 63, 0)).isEqualTo("minecraft:stone");
        assertThat(h.error("build_batch", "steps", "disc 0,63,0 r=2 no_such_grass", "dryRun", true))
                .contains("unknown block no_such_grass");
    }

    @Test
    void errorsNameTheLine() {
        assertThat(h.error("build_batch", "steps", "fill 0,64,0 1,64,1 stone\ncylinder 0,64,0 h=3 stone"))
                .isEqualTo("error: line 2 (cylinder 0,64,0 h=3 stone): cylinder needs r=N");
        assertThat(h.error("build_batch", "steps", "spiral 0,64,0 stone")).contains("unknown step \"spiral\"");
        assertThat(h.error("build_batch", "steps", "disc 0,64,0 r=3 stone dirt")).contains("did not understand \"dirt\"");
        assertThat(h.error("build_batch", "steps", "line 0,64,0 9,64,0 stone colour=red")).contains("line does not take colour=");
        assertThat(h.error("build_batch", "steps", "structure 0,64,0\nlegend: #=stone\ny=0\n#")).contains("no closing \"end\"");
    }

    @Test
    void replaceOnlyTouchesMatchingBlocks() {
        h.ok("build_batch", "steps", "set 0,64,0 glass");
        String out = h.ok("build_batch", "steps", "disc 0,64,0 r=1 sand replace=air");
        assertThat(out).contains("8 blocks changed");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:glass");
        assertThat(h.agent.get(1, 64, 0)).isEqualTo("minecraft:sand");
    }

    @Test
    void undoSkipsPartsTheAgentNoLongerKeeps() {
        // Over 1,048,576 blocks: two agent parts, x 0..127 and x 128.
        h.ok("build_batch", "steps", "fill 0,64,0 128,127,127 glass");
        h.agent.forgetOldestEdit();
        assertThat(h.ok("build_undo", "edit", "e1"))
                .isEqualTo("undid e1 partly: 8192 blocks restored, 1 of 2 parts no longer kept by the server"
                        + " (it keeps the last 8,388,608 changed blocks)");
        assertThat(h.agent.get(128, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:glass");
        assertThat(h.error("build_undo", "edit", "e1")).contains("unknown edit e1");
    }

    @Test
    void undoKeepsTheRestAfterAFailedPart() {
        h.ok("build_batch", "steps", "fill 0,64,0 128,127,127 glass");
        h.ok("build_batch", "steps", "set 0,200,0 stone");
        h.agent.failUndo(0);
        assertThat(h.error("build_undo", "edit", "e1")).contains("timed out");
        h.agent.failUndo(1);
        assertThat(h.error("build_undo", "edit", "e1")).isEqualTo("error: undo of e1 stopped after 8192 blocks restored:"
                + " timed out; build_undo edit=e1 again undoes the rest");
        assertThat(h.agent.get(128, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:glass");
        assertThat(h.ok("build_undo", "edit", "e1")).isEqualTo("undid e1: 1048576 blocks restored");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(128, 64, 0)).isEqualTo("minecraft:air");
    }

    @Test
    void replaceAlsoLimitsSetStructureAndCopy() {
        h.ok("build_batch", "steps", "set 0,64,0 glass\nset 1,64,0 glass");
        h.ok("build_batch", "steps", "set 0,64,0 torch replace=air\nset 0,65,0 torch replace=air");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:glass");
        assertThat(h.agent.get(0, 65, 0)).isEqualTo("minecraft:torch");

        h.ok("build_batch", "steps", "structure 0,64,0 replace=air\nlegend: #=stone\ny=0\n###\nend");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:glass");
        assertThat(h.agent.get(2, 64, 0)).isEqualTo("minecraft:stone");

        h.ok("build_batch", "steps", "copy 0,64,0 2,64,0 to=0,64,0 replace=stone");
        h.ok("build_batch", "steps", "set 1,64,5 sand\ncopy 0,64,0 2,64,0 to=0,64,5 replace=air");
        assertThat(h.agent.get(0, 64, 5)).isEqualTo("minecraft:glass");
        assertThat(h.agent.get(1, 64, 5)).isEqualTo("minecraft:sand");
        assertThat(h.agent.get(2, 64, 5)).isEqualTo("minecraft:stone");
    }

    @Test
    void stampRepeatsAndTurnsToFaceACentre() throws Exception {
        String tower = "legend: #=stone d=oak_stairs[facing=north]\ny=0\n#d#\n###\nend";
        String out = h.ok("build_batch", "steps",
                "structure 0,64,-10 10,64,0 0,64,10 -10,64,0 face=0,0 anchor=center\n" + tower);
        assertThat(out).startsWith("edit e1: 24 blocks changed");
        // The stair row (north side) of each copy points at the centre.
        assertThat(h.agent.get(0, 64, 9)).isEqualTo("minecraft:oak_stairs[facing=north]");
        assertThat(h.agent.get(0, 64, 10)).isEqualTo("minecraft:stone");
        assertThat(h.agent.get(0, 64, -10)).isEqualTo("minecraft:oak_stairs[facing=south]");
        assertThat(h.agent.get(0, 64, -11)).isEqualTo("minecraft:stone");
        assertThat(h.agent.get(9, 64, 0)).isEqualTo("minecraft:oak_stairs[facing=west]");
        assertThat(h.agent.get(-10, 64, 0)).isEqualTo("minecraft:oak_stairs[facing=east]");

        Files.writeString(workspace.resolve("post.txt"), "legend: #=oak_fence\ny=0\n#\ny=1\n#");
        h.ok("build_batch", "steps", "structure 0,70,0 repeat=5 step=4,0,0 file=post.txt");
        for (int i = 0; i < 5; i++) {
            assertThat(h.agent.get(4 * i, 71, 0)).isEqualTo("minecraft:oak_fence");
        }
        assertThat(h.agent.get(2, 70, 0)).isEqualTo("minecraft:air");
        assertThat(h.error("build_batch", "steps", "structure 0,64,0 repeat=3\n" + tower)).contains("step=");
        assertThat(h.error("build_batch", "steps", "structure 0,64,0 file=missing.txt")).contains("no file");
    }

    @Test
    void bigBuildsAreSplitButUndoneAsOne() {
        // The box spans more than one protocol call; only the two parts holding blocks are written.
        String out = h.ok("build_batch", "steps", "set 0,64,0 stone\nset 200,90,200 andesite");
        assertThat(out).startsWith("edit e1: 2 blocks changed");
        assertThat(h.agent.writes).hasSize(3);
        h.ok("build_undo", "edit", "e1");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(200, 90, 200)).isEqualTo("minecraft:air");
        assertThat(h.error("build_batch", "steps", "fill 0,0,0 2000,10,2000 stone")).contains("Split it");
    }

    @Test
    void aPartThatTimesOutMidWayIsUndoneWithTheRest() {
        // Writes: the name check, the first part, then the second part, which times out after one block (#275).
        h.agent.timeOutWrite(2);
        String err = h.error("build_batch", "steps", "set 0,64,0 stone\nfill 200,90,200 201,90,201 andesite");
        // The agent's own undo id means nothing to build_undo, and the blocks are undone already (#292).
        assertThat(err).isEqualTo("error: agent answered timeout: main thread did not finish within 120s");
        assertThat(h.agent.writes).hasSize(3);
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(200, 90, 200)).isEqualTo("minecraft:air");
        assertThat(h.agent.blocks.values()).allMatch("minecraft:air"::equals);
    }

    @Test
    void aFailedCleanUpLeavesABuildUndoIdForWhatStayed() {
        // The second part times out after one block, then undoing that partial edit fails too (#292).
        h.agent.timeOutWrite(2);
        h.agent.failUndo(0);
        String err = h.error("build_batch", "steps", "set 0,64,0 stone\nfill 200,90,200 201,90,201 andesite");
        assertThat(err).isEqualTo("error: agent answered timeout: main thread did not finish within 120s;"
                + " undoing it failed (timed out), build_undo edit=e1 removes the rest");
        assertThat(h.agent.get(0, 64, 0)).isEqualTo("minecraft:air");
        assertThat(h.agent.get(200, 90, 200)).isEqualTo("minecraft:andesite");
        assertThat(h.ok("build_undo", "edit", "e1")).isEqualTo("undid e1: 1 blocks restored");
        assertThat(h.agent.blocks.values()).allMatch("minecraft:air"::equals);
    }

    @Test
    @Timeout(10)
    void coordinatesAtTheIntLimitAreRefusedInsteadOfLoopingForever() {
        assertThat(h.error("build_batch", "steps", "fill 2147483647,64,0 2147483647,64,0 stone"))
                .contains("x=2147483647 is outside the world");
        assertThat(h.error("build_batch", "steps", "set 29999999,64,0 stone repeat=2 step=2,0,0"))
                .contains("x=30000001 is outside the world");
        assertThat(h.error("build_batch", "steps", "structure 29999999,64,0 repeat=2 step=2,0,0\nlegend: #=stone\n#\nend"))
                .contains("x=30000001 is outside the world");
    }
}
