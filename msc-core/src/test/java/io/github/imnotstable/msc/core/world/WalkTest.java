package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import org.junit.jupiter.api.Test;

class WalkTest {
    private static final BlockPos FROM = new BlockPos(0, 1, 0);
    private static final BlockPos TO = new BlockPos(9, 1, 0);

    /** One row along x: the floor at y=0, then the layers above it, each 10 wide. */
    private static Walk row(String floor, String... above) {
        StringBuilder text = new StringBuilder("legend: #=stone f=oak_fence H=ladder i=iron_door L=lava\ny=0\n")
                .append(floor).append('\n');
        for (int y = 0; y < 4; y++) {
            text.append("y=").append(y + 1).append('\n').append(y < above.length ? above[y] : "..........").append('\n');
        }
        return new Walk(TextGrid.parse(text.toString(), "world", new BlockPos(0, 0, 0)));
    }

    @Test
    void walksAndStepsUp() {
        assertThat(row("##########").path(FROM, TO)).isEqualTo("reachable: 9 moves\nroute: 0,1,0 > 9,1,0");
        assertThat(row("##########", ".....#####").path(FROM, new BlockPos(9, 2, 0)))
                .isEqualTo("reachable: 9 moves, 1 up\nroute: 0,1,0 > 4,1,0 > 5,2,0 > 9,2,0");
        // The floor block itself is accepted as the position.
        assertThat(row("##########").path(new BlockPos(0, 0, 0), new BlockPos(9, 0, 0))).startsWith("reachable");
    }

    @Test
    void jumpsGapsUpToThreeAndNamesWiderOnes() {
        assertThat(row("###...####").path(FROM, TO)).startsWith("reachable: 6 moves, 1 jump (widest gap 3)");
        assertThat(row("###....###").path(FROM, TO)).isEqualTo("""
                not reachable from 0,1,0 to 9,1,0. Closest: 2,1,0
                gap to the east: 4 blocks without floor from 3,1,0; a jump clears 3 on the level, 2 going up a block""");
    }

    @Test
    void namesWallsFencesDoorsAndLava() {
        assertThat(row("##########", ".....#....", ".....#....").path(FROM, TO))
                .endsWith("wall to the east: stone at 5,1,0 (2 high; a jump climbs 1)");
        assertThat(row("##########", ".....f....").path(FROM, TO))
                .endsWith("wall to the east: oak_fence at 5,1,0 (fences and walls are too tall to jump onto)");
        assertThat(row("##########", ".....i....", ".....i....").path(FROM, TO)).endsWith("iron_door at 5,1,0 (opens only with redstone)");
        assertThat(row("##########", ".....#....", "..........", "....#.....").path(FROM, TO))
                .endsWith("(1 high, but no headroom to jump: stone above 4,1,0)");
        assertThat(row("#####L####", ".....L....").path(FROM, TO)).endsWith("lava at 5,1,0 to the east");
    }

    @Test
    void climbsLaddersAndDropsSafely() {
        assertThat(row("##########", "....H#....", "....H#....", "....H#....").path(FROM, new BlockPos(5, 4, 0)))
                .isEqualTo("reachable: 6 moves, 2 up, 1 climbed or swum\nroute: 0,1,0 > 3,1,0 > 4,2,0 > 4,3,0 > 5,4,0");
        assertThat(row("##########", ".....#####", ".....#####").path(new BlockPos(9, 3, 0), new BlockPos(4, 1, 0)))
                .isEqualTo("reachable: 5 moves, 1 drop\nroute: 9,3,0 > 5,3,0 > 4,1,0");
    }

    @Test
    void refusesPlacesNobodyCanStand() {
        assertThatThrownBy(() -> row("##########").path(new BlockPos(0, 3, 0), TO)).isInstanceOf(ToolException.class)
                .hasMessageContaining("nobody can stand at 0,3,0");
        assertThatThrownBy(() -> row("##########").path(new BlockPos(20, 1, 0), TO)).hasMessageContaining("outside the region");
    }

    @Test
    void judgesBlocksByName() {
        assertThat(Walk.classify("minecraft:oak_door[half=lower,open=false]")).isEqualTo(Walk.classify("minecraft:air"));
        assertThat(Walk.classify("minecraft:red_carpet")).isEqualTo(Walk.classify("minecraft:air"));
        assertThat(Walk.classify("minecraft:oak_trapdoor[open=false]")).isEqualTo(Walk.classify("minecraft:stone"));
        assertThat(Walk.classify("minecraft:stone_slab[type=top]")).isEqualTo(Walk.classify("minecraft:stone"));
        assertThat(Walk.classify("minecraft:stone_slab[type=bottom]")).isEqualTo(Walk.classify("minecraft:oak_stairs"));
        assertThat(Walk.classify("minecraft:mushroom_stem")).isEqualTo(Walk.classify("minecraft:stone"));
        assertThat(Walk.classify("mymod:glowing_moss")).isEqualTo(Walk.classify("minecraft:stone"));
    }
}
