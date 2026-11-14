package com.example.verifyme;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/** Uses the names CLAUDE.md lists, so one the pinned Fabric API does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static void listeners() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { });
        ServerTickEvents.END_SERVER_TICK.register(server -> { });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> true);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> InteractionResult.PASS);
    }

    static void reply(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.help.failed").withStyle(ChatFormatting.RED));
    }

    static void world(MinecraftServer server, ServerLevel level, ServerPlayer player, BlockPos pos, int chunkX, int chunkZ) {
        server.execute(() -> level.setChunkForced(chunkX, chunkZ, true));
    }
}
