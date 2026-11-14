package com.example.verifyme;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/** Uses the names CLAUDE.md lists, so one the pinned Minestom does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static void listen() {
        MinecraftServer.getGlobalEventHandler().addListener(Event.class, e -> { });
    }

    static Component text() {
        return Component.text("Hi", NamedTextColor.GREEN);
    }

    static void build(Instance instance, int x, int y, int z) {
        instance.setBlock(x, y, z, Block.STONE);
        instance.setBlock(x, y + 1, z, Block.OAK_STAIRS.withProperty("facing", "north"));
    }
}
