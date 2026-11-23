package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class TourStopsTest {
    /** A roofed 7x7 stone hut on a stone floor at y 0, door in its south wall at 3,1,6, open ground around to z 14. */
    private static BlockGrid hut() {
        Region r = Region.of("world", -4, 0, -4, 10, 9, 14);
        int[] b = new int[Math.toIntExact(r.volume())];
        for (int z = -4; z <= 14; z++) {
            for (int x = -4; x <= 10; x++) {
                b[r.index(x, 0, z)] = 1;
                for (int y = 1; y <= 5; y++) {
                    boolean inHut = x >= 0 && x <= 6 && z >= 0 && z <= 6;
                    boolean shell = y == 5 || x == 0 || x == 6 || z == 0 || z == 6;
                    b[r.index(x, y, z)] = inHut && shell ? 1 : 0;
                }
            }
        }
        b[r.index(3, 1, 6)] = 2;
        b[r.index(3, 2, 6)] = 3;
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone",
                "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]",
                "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false]"), b);
    }

    @Test
    void stopsAtAnIssueThenOutsideAndInsideTheDoorThenAround() {
        List<TourStops.Stop> stops = TourStops.plan(hut(),
                List.of("dark rooms: 100% of the floor inside has no light", "half doors: 1 (first at -2,1,12) lack"), 6);
        assertThat(stops).extracting(TourStops.Stop::label).containsExactly("half doors at -2,1,12",
                "door at 3,1,6, from outside", "inside the door at 3,1,6", "the build from the north",
                "the build from the east", "the build from the south");
        TourStops.Stop outside = stops.get(1);
        assertThat(outside.ez()).isEqualTo(10.5); // south of the door, under the sky
        assertThat(outside.ey()).isEqualTo(1 + TourStops.EYE);
        assertThat(stops.get(2).ez()).isEqualTo(5.0); // a step inside
    }
}
