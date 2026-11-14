package com.example.verifyme;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Uses the names CLAUDE.md lists, so one the pinned NeoForge does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static void listeners() {
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, e -> { });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> { });
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, e -> { });
    }

    static void reply(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.help.failed").withStyle(ChatFormatting.RED));
    }

    static void world(MinecraftServer server, ServerLevel level, ServerPlayer player, BlockPos pos, int chunkX, int chunkZ) {
        server.execute(() -> level.setChunkForced(chunkX, chunkZ, true));
    }

    static ModConfig.Type[] configTypes() {
        return new ModConfig.Type[] {ModConfig.Type.LOCAL, ModConfig.Type.STARTUP, ModConfig.Type.SYNCED};
    }
}
