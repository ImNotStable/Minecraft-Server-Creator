package com.example.verifyme;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;

/** Uses the names CLAUDE.md lists, so one the pinned Forge does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static void listeners() {
        ServerStartingEvent.BUS.addListener(e -> { });
        TickEvent.ServerTickEvent.Post.BUS.addListener(e -> { });
        // The two one-argument listener kinds of a cancellable event, as typed values.
        Consumer<BlockEvent.BreakEvent> watch = e -> { };
        Predicate<BlockEvent.BreakEvent> cancel = e -> false;
        BlockEvent.BreakEvent.BUS.addListener(watch);
        BlockEvent.BreakEvent.BUS.addListener(cancel);
    }

    static void reply(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.help.failed").withStyle(ChatFormatting.RED));
    }

    static void world(MinecraftServer server, ServerLevel level, ServerPlayer player, BlockPos pos, int chunkX, int chunkZ) {
        server.execute(() -> level.setChunkForced(chunkX, chunkZ, true));
    }
}
