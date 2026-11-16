package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PaperWorldsTest {
    private PaperWorlds worlds;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        WorldMock overworld = new WorldMock(Material.AIR, 0); // keyed minecraft:overworld
        overworld.setName("world");
        server.addWorld(overworld);
        WorldMock nether = new WorldMock(WorldCreator.ofKey(NamespacedKey.minecraft("the_nether")));
        nether.setName("world_nether");
        server.addWorld(nether);
        worlds = new PaperWorlds(server, new DataCommands((line, out) -> false));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void findsAWorldByNameOrByDimensionId() {
        assertThat(worlds.byName("world_nether")).map(PaperWorld::name).hasValue("world_nether");
        assertThat(worlds.byName("minecraft:the_nether")).map(PaperWorld::name).hasValue("world_nether");
        assertThat(worlds.byName("minecraft:overworld")).map(PaperWorld::name).hasValue("world");
        assertThat(worlds.byName("minecraft:the_end")).isEmpty();
        assertThat(worlds.byName("Not A Key!")).isEmpty();
    }
}
