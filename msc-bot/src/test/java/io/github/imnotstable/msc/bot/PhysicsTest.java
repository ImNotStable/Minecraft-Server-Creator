package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhysicsTest {
    /** Stone up to y=0 (so the ground surface is y=1), with blocks set by "x,y,z". */
    private static final class Flat implements BlockSource {
        final Map<String, String> blocks = new HashMap<>();
        int reads;

        Flat set(int x, int y, int z, String state) {
            blocks.put(x + "," + y + "," + z, state);
            return this;
        }

        Flat gap(int x1, int x2) {
            for (int x = x1; x <= x2; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = -8; y <= 0; y++) {
                        set(x, y, z, "minecraft:air");
                    }
                }
            }
            return this;
        }

        @Override
        public BlockGrid read(Region r) {
            reads++;
            List<String> palette = new ArrayList<>();
            int[] cells = new int[(int) r.volume()];
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        String s = blocks.getOrDefault(x + "," + y + "," + z, y <= 0 ? "minecraft:stone" : "minecraft:air");
                        int i = palette.indexOf(s);
                        if (i < 0) {
                            palette.add(s);
                            i = palette.size() - 1;
                        }
                        cells[r.index(x, y, z)] = i;
                    }
                }
            }
            return new BlockGrid(r, palette, cells);
        }
    }

    private static Physics at(double x, double y, double z) {
        Physics p = new Physics();
        p.x = x;
        p.y = y;
        p.z = z;
        return p;
    }

    /** yaw -90 faces +x. */
    private static void run(Physics p, WorldView w, int ticks, double forward, boolean jump, boolean sprint)
            throws Exception {
        for (int i = 0; i < ticks; i++) {
            p.tick(new Physics.Input(forward, 0, jump, sprint, -90), w);
        }
    }

    @Test
    void fallsOntoTheGround() throws Exception {
        Physics p = at(0.5, 4, 0.5);
        run(p, new WorldView(new Flat(), "w"), 40, 0, false, false);
        assertThat(p.y).isEqualTo(1.0);
        assertThat(p.onGround).isTrue();
    }

    @Test
    void walksAndSprintsAtVanillaSpeed() throws Exception {
        WorldView w = new WorldView(new Flat(), "w");
        Physics walk = at(0.5, 1, 0.5);
        run(walk, w, 40, 1, false, false);
        double x0 = walk.x;
        run(walk, w, 20, 1, false, false);
        assertThat(walk.x - x0).isCloseTo(4.317, within(0.05)); // blocks per second
        assertThat(walk.z).isEqualTo(0.5);
        Physics sprint = at(0.5, 1, 0.5);
        run(sprint, w, 40, 1, false, true);
        x0 = sprint.x;
        run(sprint, w, 20, 1, false, true);
        assertThat(sprint.x - x0).isCloseTo(5.612, within(0.05));
    }

    @Test
    void jumpsAboutOneAndAQuarterBlocks() throws Exception {
        WorldView w = new WorldView(new Flat(), "w");
        Physics p = at(0.5, 1, 0.5);
        run(p, w, 2, 0, false, false);
        double top = p.y;
        p.tick(new Physics.Input(0, 0, true, false, 0), w);
        for (int i = 0; i < 20; i++) {
            top = Math.max(top, p.y);
            p.tick(Physics.Input.NONE, w);
        }
        assertThat(top - 1).isCloseTo(1.2522, within(0.01));
        assertThat(p.y).isEqualTo(1.0);
    }

    @Test
    void stepsOntoSlabsButNotFullBlocks() throws Exception {
        Flat flat = new Flat().set(3, 1, 0, "minecraft:stone_slab[type=bottom]").set(3, 1, 2, "minecraft:stone");
        WorldView w = new WorldView(flat, "w");
        Physics slab = at(0.5, 1, 0.5);
        run(slab, w, 40, 1, false, false);
        assertThat(slab.x).isGreaterThan(5);
        assertThat(slab.y).isEqualTo(1.0); // stepped up and down again
        Physics wall = at(0.5, 1, 2.5);
        run(wall, w, 40, 1, false, false);
        assertThat(wall.x).isCloseTo(2.7, within(0.001));
        assertThat(wall.horizontalCollision).isTrue();
    }

    @Test
    void aSprintJumpClearsThreeBlocksButNotFive() throws Exception {
        assertThat(sprintJumpOver(3)).isTrue();
        assertThat(sprintJumpOver(5)).isFalse();
    }

    /** Sprint from x=0.5 and jump at the edge of a gap starting at x=5; true when the player lands past it. */
    private static boolean sprintJumpOver(int width) throws Exception {
        Flat flat = new Flat().gap(5, 4 + width);
        WorldView w = new WorldView(flat, "w");
        Physics p = at(0.5, 1, 0.5);
        for (int i = 0; i < 100; i++) {
            boolean edge = p.onGround && !p.supportedAt(p.x + p.vx * 1.8, p.z, w) && p.x < 5 + width;
            p.tick(new Physics.Input(1, 0, edge, true, -90), w);
            if (p.y < -2) {
                return false;
            }
        }
        return p.y == 1.0 && p.x > 5 + width;
    }
}
