package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Block entity data on Paper goes through the server's {@code /data} command (ADR 0042). MockBukkit has no vanilla
 * commands, so these check the command lines and how their answers are read; the commands themselves need a live run.
 */
class DataCommandsTest {
    private final List<String> lines = new ArrayList<>();
    private String answer = "";
    private ServerMock server;
    private WorldOps<?> ops;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        WorldMock w = new WorldMock(Material.AIR, 0);
        w.setName("world");
        server.addWorld(w);
        DataCommands data = new DataCommands((line, out) -> {
            lines.add(line);
            out.accept(answer);
            return true;
        });
        ops = new WorldOps<>(new PaperWorlds(server, data), MainThread.inline(), new EditHistory<>(1_000_000));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void aWriteWithDataPlacesTheBlockThenMergesInItsWorld() {
        answer = "Modified block data of 1, 70, 2";
        Region one = Region.of("world", 1, 70, 2, 1, 70, 2);

        ops.write(new Ops.WriteParams(new BlockGrid(one, List.of("minecraft:chest{Lock:\"k\"}"), new int[] {0}), false));

        assertThat(lines).containsExactly(
                "execute in " + server.getWorld("world").getKey().asString() + " run data merge block 1 70 2 {Lock:\"k\"}");
    }

    @Test
    void aMergeThatChangesNothingIsFineAndAnyOtherAnswerFails() {
        DataCommands data = new DataCommands((line, out) -> {
            out.accept(answer);
            return true;
        });
        answer = "Nothing changed. The specified properties already have these values";
        data.merge("minecraft:overworld", 0, 0, 0, "{}");
        answer = "The target block is not a block entity";
        assertThatThrownBy(() -> data.merge("minecraft:overworld", 0, 0, 0, "{}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a block entity");
    }

    @Test
    void theSnbtIsTakenFromTheQueryAnswer() {
        assertThat(DataCommands.snbt("1, 70, 2 has the following block data: {Items: [], id: \"minecraft:chest\"}"))
                .isEqualTo("{Items: [], id: \"minecraft:chest\"}");
        assertThat(DataCommands.snbt("The target block is not a block entity")).isNull();
    }

    @Test
    void onlyBlockEntitiesTakeData() {
        BlockData chest = server.createBlockData(Material.CHEST);
        BlockData stone = server.createBlockData(Material.STONE);
        assertThat(PaperWorld.holds(chest)).isTrue();
        assertThat(PaperWorld.holds(stone)).isFalse();
        PaperWorlds worlds = new PaperWorlds(server, new DataCommands((line, out) -> false));
        assertThatThrownBy(() -> worlds.parseData(stone, "{a:1}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no block entity data");
    }
}
