package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What each loader's {@code Stdlib.init()} calls from its events, the same on Fabric, NeoForge and Forge, so the
 * behaviour lives in one place.
 */
final class Hooks {
    private static final TickScheduler SCHEDULER = new TickScheduler();
    private static MinecraftServer server;

    private Hooks() {}

    static TickScheduler scheduler() {
        return SCHEDULER;
    }

    static MinecraftServer server() {
        if (server == null) {
            throw new IllegalStateException("No server yet: call Stdlib.init() in your mod's initializer and use this "
                    + "once the server starts");
        }
        return server;
    }

    /** The server, or null before it starts. */
    static MinecraftServer serverOrNull() {
        return server;
    }

    /** The game built its command dispatcher (at start and on every /reload): add the library's commands. */
    static void commands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        Cmd.attachAll(dispatcher);
    }

    /** A player right-clicked an entity; true when the library handled it (an NPC), so the game should not. */
    static boolean interact(ServerPlayer p, net.minecraft.world.entity.Entity target) {
        return Npcs.interact(p, target);
    }

    static void starting(MinecraftServer s) {
        server = s;
    }

    static void stopping() {
        PlayerData.stopping();
    }

    static void stopped() {
        server = null;
    }

    static void tick(MinecraftServer s) {
        SCHEDULER.tick();
        Regions.tick(s);
    }

    static void joined(ServerPlayer p) {
        PlayerData.joined(p.getUUID());
    }

    static void left(ServerPlayer p) {
        PlayerData.left(p.getUUID());
        Regions.left(p.getUUID());
        Prompt.left(p.getUUID());
        Sidebar.left(p.getUUID());
    }
}
