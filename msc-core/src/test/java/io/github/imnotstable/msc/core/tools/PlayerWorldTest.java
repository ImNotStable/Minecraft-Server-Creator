package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerWorldTest {
    private static Ops.EntityInfo player(String name) {
        return new Ops.EntityInfo("minecraft:player", "u", 0.5, 64, 0.5, name, List.of(), 20.0, 20.0);
    }

    @Test
    void findsTheWorldThatListsThePlayerNearWhereItStands() throws Exception {
        List<Region> asked = new ArrayList<>();
        // Two Minestom instances of one dimension: the agent knows which one holds the player.
        String world = PlayerTools.worldOf(List.of("lobby", "arena"), "MscTester", new BlockPos(0, 64, 0), r -> {
            asked.add(r);
            return r.world().equals("arena") ? List.of(player("Other"), player("MscTester")) : List.of(player("Other"));
        });
        assertThat(world).isEqualTo("arena");
        int reach = PlayerTools.LOCATE_REACH;
        assertThat(asked.getLast()).isEqualTo(Region.of("arena", -reach, 64 - reach, -reach, reach, 64 + reach, reach));
        assertThat(PlayerTools.worldOf(List.of("lobby"), "MscTester", new BlockPos(0, 64, 0), r -> List.of())).isNull();
    }
}
