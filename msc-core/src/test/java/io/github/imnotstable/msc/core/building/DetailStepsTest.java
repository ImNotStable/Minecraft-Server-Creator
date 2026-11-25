package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BuildReview;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Joins, gradients and the detail steps of ADR 0077, drawn over an empty world with stone below y 0. */
class DetailStepsTest {
    private static BlockGrid world(Region r) {
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    blocks[r.index(x, y, z)] = y < 0 ? 1 : 0;
                }
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone"), blocks);
    }

    private static Canvas draw(String steps) throws Exception {
        List<Step> parsed = new BuildScript("world", null).parse(steps);
        return Builder.drawJoined(new Step.Group(parsed).bounds(), parsed, DetailStepsTest::world);
    }

    private static String at(Canvas c, int x, int y, int z) {
        String s = c.planned(x, y, z);
        return s == null ? null : s.replace("minecraft:", "");
    }

    @Test
    void fencesPanesAndWallsConnect() throws Exception {
        Canvas c = draw("""
                set 0,0,0 stone
                fill 1,0,0 3,0,0 oak_fence
                set 4,0,0 oak_fence_gate[facing=east,open=false]
                fill 0,1,0 2,1,0 glass_pane
                fill 0,2,0 2,2,0 cobblestone_wall
                set 2,2,1 cobblestone_wall
                set 0,3,0 oak_fence[north=true]
                """);
        assertThat(at(c, 1, 0, 0)).contains("west=true").contains("east=true").contains("north=false");
        assertThat(at(c, 3, 0, 0)).contains("east=true"); // the gate
        assertThat(at(c, 1, 1, 0)).contains("west=true").contains("east=true");
        assertThat(at(c, 1, 2, 0)).contains("up=false").contains("east=low");
        assertThat(at(c, 2, 2, 0)).contains("up=true").contains("south=low"); // turns a corner: a post
        assertThat(at(c, 0, 3, 0)).isEqualTo("oak_fence[north=true]"); // said its own: kept
    }

    @Test
    void aFenceInTheWorldTurnsToMeetANewOne() throws Exception {
        // The world holds a fence post at 0,0,0; this build continues the line east of it.
        Canvas.WorldReader withPost = r -> {
            BlockGrid g = world(r);
            int[] blocks = g.blocks().clone();
            if (r.contains(0, 0, 0)) {
                blocks[r.index(0, 0, 0)] = 2;
            }
            return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone",
                    "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"), blocks);
        };
        List<Step> parsed = new BuildScript("world", null).parse("fill 1,0,0 2,0,0 oak_fence");
        Canvas c = Builder.drawJoined(new Step.Group(parsed).bounds(), parsed, withPost);
        assertThat(at(c, 1, 0, 0)).contains("west=true");
        assertThat(at(c, 0, 0, 0)).contains("east=true").contains("west=false");
    }

    @Test
    void stairsJoinAtCorners() throws Exception {
        Canvas c = draw("""
                fill 0,0,0 4,0,4 stone
                fill 0,2,0 2,2,0 oak_stairs[facing=south,half=bottom]
                fill 0,2,1 0,2,2 oak_stairs[facing=east,half=bottom]
                """);
        assertThat(at(c, 0, 2, 0)).contains("shape=outer_");
        assertThat(at(c, 1, 2, 0)).doesNotContain("shape="); // straight, the default
    }

    @Test
    void doorsBedsAndTallPlantsGetTheirOtherHalf() throws Exception {
        Canvas c = draw("""
                fill 0,0,0 0,2,0 stone
                fill 2,0,0 2,2,0 stone
                set 1,0,0 oak_door[facing=north,hinge=left,open=false]
                set 5,0,5 red_bed[facing=east]
                set 8,0,8 rose_bush
                """);
        assertThat(at(c, 1, 0, 0)).contains("half=lower");
        assertThat(at(c, 1, 1, 0)).contains("oak_door").contains("half=upper");
        assertThat(at(c, 5, 0, 5)).contains("part=foot");
        assertThat(at(c, 6, 0, 5)).contains("part=head");
        assertThat(at(c, 8, 1, 8)).contains("half=upper");
    }

    @Test
    void aDoorGivenAsItsLowerHalfGetsItsTopOrIsRefused() throws Exception {
        // Run 6's customs office: the door's half said, its top left as air, so only the bottom was placed.
        Canvas c = draw("""
                set 1,1,0 air
                set 1,0,0 spruce_door[facing=east,half=lower,hinge=left,open=false]
                """);
        assertThat(at(c, 1, 1, 0)).contains("spruce_door").contains("half=upper").contains("hinge=left");
        // A floor laid at the door's top takes the room it needs: nothing is placed.
        assertThatThrownBy(() -> draw("""
                set 1,0,0 spruce_door[facing=east,half=lower,hinge=left,open=false]
                fill 0,1,0 2,1,0 oak_planks
                """)).isInstanceOf(ToolException.class)
                .hasMessageContaining("1 door has no room for the other half (spruce_door at 1,0,0 has oak_planks above)");
    }

    @Test
    void theReviewFindsHalfAndBlockedDoors() throws Exception {
        // A door whose top was cut off by a floor, and a whole door with the floor inside at its bottom half.
        Region r = Region.of("world", 0, 0, 0, 4, 3, 4);
        int[] blocks = new int[Math.toIntExact(r.volume())];
        blocks[r.index(1, 1, 1)] = 1;
        blocks[r.index(1, 2, 1)] = 3;
        blocks[r.index(3, 1, 1)] = 1;
        blocks[r.index(3, 2, 1)] = 2;
        blocks[r.index(4, 1, 1)] = 3;
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:air",
                "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false]",
                "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false]", "minecraft:oak_planks"), blocks);
        String[] names = {null, "oak_door", "oak_door", "oak_planks"};
        assertThat(String.join("\n", BuildReview.doors(grid, names)))
                .contains("half doors: 1 (first at 1,1,1)").contains("blocked doors: 1 (first at 3,1,1)");
    }

    @Test
    void theReviewFindsWindowsLeftOpenButNotACorridor() {
        // A plank wall at z 2 with four window holes at y 2; then the same wall with two of them glazed.
        Region r = Region.of("world", 0, 0, 0, 8, 4, 4);
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = 0; y <= 4; y++) {
            for (int x = 0; x <= 8; x++) {
                blocks[r.index(x, y, 2)] = y == 2 && x % 2 == 1 ? 0 : 1;
            }
        }
        List<String> palette = List.of("minecraft:air", "minecraft:oak_planks", "minecraft:glass_pane");
        String[] names = {null, "oak_planks", "glass_pane"};
        assertThat(BuildReview.doors(new BlockGrid(r, palette, blocks), names)).singleElement().asString()
                .startsWith("open holes: 4 gaps through walls with nothing in them (first at 1,2,2)");
        blocks[r.index(1, 2, 2)] = 2;
        blocks[r.index(3, 2, 2)] = 2;
        assertThat(BuildReview.doors(new BlockGrid(r, palette, blocks), names)).isEmpty();
        // A 1-wide covered corridor along z between two thick walls is not a hole.
        int[] corridor = new int[Math.toIntExact(r.volume())];
        for (int z = 0; z <= 4; z++) {
            for (int y = 0; y <= 4; y++) {
                for (int x = 0; x <= 8; x++) {
                    corridor[r.index(x, y, z)] = x == 4 && y >= 1 && y <= 2 ? 0 : 1;
                }
            }
        }
        assertThat(BuildReview.doors(new BlockGrid(r, palette, corridor), names)).isEmpty();
    }

    @Test
    void decoratedBlocksTurnToWhereEachLands() throws Exception {
        Canvas c = draw("""
                fill 0,0,0 0,4,4 stone
                fill 1,4,0 4,4,4 stone
                decorate 3,3,3 3,3,3 lantern on=ceiling chance=100
                decorate 1,3,1 1,3,1 torch on=wall chance=100 length=1
                decorate 1,3,3 1,3,3 spruce_trapdoor on=wall chance=100 length=1
                decorate 1,0,2 1,0,2 chest on=top chance=100
                """);
        assertThat(at(c, 3, 3, 3)).contains("hanging=true");
        assertThat(at(c, 1, 3, 1)).startsWith("wall_torch").contains("facing=east");
        assertThat(at(c, 1, 3, 3)).contains("half=top"); // under a ceiling
        assertThat(at(c, 1, 0, 2)).contains("facing=east");
    }

    @Test
    void writtenBlocksSayHowTheyArePlaced() throws Exception {
        assertThatThrownBy(() -> draw("set 0,0,0 oak_stairs")).isInstanceOf(ToolException.class)
                .hasMessageContaining("oak_stairs needs facing=north|east|south|west half=bottom|top");
        assertThatThrownBy(() -> draw("fill 0,0,0 3,3,3 oak_planks>oak_log")).hasMessageContaining("oak_log needs axis=x|y|z");
        assertThatThrownBy(() -> draw("structure 0,0,0\nlegend: l=lantern\ny=0\nl\nend")).hasMessageContaining("hanging=");
        assertThatThrownBy(() -> draw("set 0,0,0 oak_stairs\nfill 1,0,0 2,0,0 oak_log\nset 3,0,0 stone"))
                .hasMessageStartingWith("nothing placed").hasMessageContaining("line 1: oak_stairs needs")
                .hasMessageContaining("line 2: oak_log needs axis") // every line at once, one round trip
                .hasMessageContaining("such as oak_stairs[facing=north,half=bottom];");
        // The example is the refused block's own, not a stairs for a sign.
        assertThatThrownBy(() -> draw("set 0,0,0 oak_sign")).hasMessageContaining("such as oak_sign[rotation=0];");
        assertThat(BlockRules.missing("oak_log[axis=x]")).isEmpty();
        assertThat(BlockRules.missing("stone_bricks")).isEmpty();
        assertThat(BlockRules.missing("oak_fence")).isEmpty(); // connections come from the neighbours
        assertThat(BlockRules.missing("mymod:odd_stairs")).isEmpty();
        assertThat(BlockRules.missing("spruce_trapdoor[facing=east]")).containsExactly("half=bottom|top", "open=true|false");
        // Beside water, whether a block holds water is a choice too.
        assertThatThrownBy(() -> draw("set 0,0,0 water\nset 1,0,0 oak_slab[type=bottom]"))
                .hasMessageContaining("waterlogged=true|false");
        assertThat(at(draw("set 0,0,0 water\nset 1,0,0 oak_slab[type=bottom,waterlogged=true]"), 1, 0, 0))
                .contains("waterlogged=true");
        assertThat(at(draw("set 5,0,5 oak_slab[type=top]"), 5, 0, 5)).isEqualTo("oak_slab[type=top]");
    }

    @Test
    void aGradientRunsUpTheBoxWithoutSkippingAStage() throws Exception {
        Canvas c = draw("fill 0,0,0 15,11,15 deepslate_bricks>stone_bricks>andesite");
        int bottom = 0;
        int top = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                bottom += at(c, x, 0, z).equals("deepslate_bricks") ? 1 : 0;
                top += at(c, x, 11, z).equals("andesite") ? 1 : 0;
                for (int y = 0; y < 11; y++) {
                    // The darkest and lightest stage never touch: the middle one is always between.
                    assertThat(at(c, x, y, z).equals("deepslate_bricks") && at(c, x, y + 1, z).equals("andesite")).isFalse();
                }
            }
        }
        assertThat(bottom).isGreaterThan(200);
        assertThat(top).isGreaterThan(200);
        Canvas sideways = draw("fill 0,0,0 9,0,0 oak_planks>spruce_planks grade=-x");
        assertThat(at(sideways, 0, 0, 0)).isEqualTo("spruce_planks");
        assertThat(at(sideways, 9, 0, 0)).isEqualTo("oak_planks");
    }

    @Test
    void trimDressesOnlyTheOutsideOfTheWalls() throws Exception {
        Canvas c = draw("""
                fill 0,0,0 6,4,6 stone_bricks hollow
                set 3,2,0 air
                trim 0,0,0 6,4,6 stone_brick_stairs at=top
                trim 0,0,0 6,4,6 spruce_trapdoor at=shutters
                trim 0,0,0 6,4,6 stone_brick_slab at=sills
                trim 0,0,0 6,4,6 polished_andesite at=corners
                """);
        assertThat(at(c, 3, 4, -1)).contains("stone_brick_stairs").contains("facing=south").contains("half=top");
        assertThat(at(c, 3, 3, 1)).isEqualTo("air"); // the inside is left alone
        assertThat(at(c, 2, 2, -1)).contains("spruce_trapdoor").contains("open=true").contains("facing=north");
        assertThat(at(c, 4, 2, -1)).contains("spruce_trapdoor");
        assertThat(at(c, 3, 1, -1)).isEqualTo("stone_brick_slab[type=top]");
        assertThat(at(c, 0, 2, 0)).isEqualTo("polished_andesite");
        assertThat(at(c, -1, 4, -1)).contains("stone_brick_stairs"); // the corner piece
        assertThatThrownBy(() -> draw("trim 0,0,0 1,1,1 stone at=roof")).isInstanceOf(ToolException.class)
                .hasMessageContaining("at=roof");
    }

    @Test
    void decorateHangsFromWallsAndCeilings() throws Exception {
        Canvas c = draw("""
                fill 0,0,0 0,8,8 stone
                fill 1,8,0 8,8,8 stone
                decorate 1,0,0 1,7,8 vine on=wall chance=100 length=3 seed=2
                decorate 2,0,0 8,7,8 chain on=ceiling spacing=4 length=2 tip=lantern
                """);
        assertThat(at(c, 1, 7, 4)).startsWith("vine").contains("west=true");
        assertThat(at(c, 1, 5, 4)).startsWith("vine");
        assertThat(at(c, 1, 4, 4)).isNull(); // three long
        List<String> lanterns = new ArrayList<>();
        for (int z = 0; z <= 8; z++) {
            for (int x = 2; x <= 8; x++) {
                if (at(c, x, 7, z) != null) {
                    assertThat(at(c, x, 7, z)).startsWith("chain");
                    lanterns.add(at(c, x, 6, z));
                }
            }
        }
        assertThat(lanterns).isNotEmpty().allMatch(s -> s.startsWith("lantern") && s.contains("hanging=true"));
    }

    @Test
    void weatherMossesTheBaseAndCracksTheEdges() throws Exception {
        Canvas c = draw("""
                fill 0,0,0 20,15,0 stone_bricks
                weather 0,0,0 20,15,0 amount=60 seed=4
                """);
        int lowMoss = 0;
        int highMoss = 0;
        int cracked = 0;
        for (int x = 0; x <= 20; x++) {
            for (int y = 0; y <= 15; y++) {
                String s = at(c, x, y, 0);
                lowMoss += y < 4 && s.equals("mossy_stone_bricks") ? 1 : 0;
                highMoss += y > 11 && s.equals("mossy_stone_bricks") ? 1 : 0;
                cracked += s.equals("cracked_stone_bricks") ? 1 : 0;
            }
        }
        assertThat(lowMoss).isGreaterThan(highMoss);
        assertThat(cracked).isPositive();
        assertThat(Detail.oxidise("cut_copper_stairs", 2)).isEqualTo("weathered_cut_copper_stairs");
        assertThat(Detail.oxidise("waxed_copper_block", 1)).isEqualTo("waxed_exposed_copper");
        assertThat(Detail.oxidise("oxidized_copper", 1)).isNull();
        assertThat(Detail.oxidise("stone", 1)).isNull();
        Canvas broken = draw("fill 0,0,0 20,5,0 stone_bricks\nweather 0,0,0 20,5,0 amount=0 break=80 chip=stone_brick_slab");
        long slabs = 0;
        for (int x = 0; x <= 20; x++) {
            slabs += "stone_brick_slab".equals(at(broken, x, 5, 0)) ? 1 : 0;
        }
        assertThat(slabs).isPositive();
    }

    @Test
    void roughShapesBulgeButRepeat() throws Exception {
        Canvas round = draw("sphere 0,0,0 r=6 stone");
        Canvas rough = draw("sphere 0,0,0 r=6 stone rough=2 seed=3");
        assertThat(rough.placed()).isNotEqualTo(round.placed());
        assertThat(draw("sphere 0,0,0 r=6 stone rough=2 seed=3").placed()).isEqualTo(rough.placed());
    }

    @Test
    void theReviewAsksForFinishOnAPlainBox() throws Exception {
        Canvas plain = draw("""
                fill 0,0,0 11,0,11 stone
                fill 0,1,0 11,6,11 stone_bricks hollow
                """);
        Region r = Region.of("world", -2, 0, -2, 13, 9, 13);
        String text = BuildReview.review(grid(plain, r));
        assertThat(text).contains("plain wall tops").contains("fix first").contains("empty rooms").contains("no plants");
    }

    private static BlockGrid grid(Canvas c, Region r) {
        List<String> palette = new ArrayList<>(List.of("minecraft:air"));
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String s = c.planned(x, y, z);
                    if (s == null) {
                        s = "minecraft:air";
                    }
                    int i = palette.indexOf(s);
                    if (i < 0) {
                        palette.add(s);
                        i = palette.size() - 1;
                    }
                    blocks[r.index(x, y, z)] = i;
                }
            }
        }
        return new BlockGrid(r, palette, blocks);
    }

    @Test
    void aBasePlinthReachesTheGroundBelowItsBox() throws Exception {
        // Sonnet 5.5 boxed hillside houses from the uphill ground: the plinth hung 3 up the wall (ADR 0109).
        Canvas c = draw("""
                fill 0,0,0 6,6,6 stone_bricks
                fill 1,0,1 5,6,5 air
                trim 0,3,0 6,6,6 cobblestone_stairs at=base
                """);
        assertThat(at(c, -1, 0, 3)).startsWith("cobblestone_stairs");
        assertThat(at(c, -1, 3, 3)).isNull();
    }
}