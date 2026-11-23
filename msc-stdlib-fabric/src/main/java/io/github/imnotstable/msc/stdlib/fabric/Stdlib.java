package io.github.imnotstable.msc.stdlib.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;

/**
 * Call {@link #init} first in {@code onInitializeServer}: it hooks the scheduler, player data, custom items, regions
 * and prompts into Fabric's events. Fabric loads one copy of this library for every mod that nests it, so calling it
 * from several mods is fine.
 */
public final class Stdlib {
    private static boolean hooked;

    private Stdlib() {}

    public static synchronized void init() {
        if (hooked) {
            return;
        }
        hooked = true;
        ServerLifecycleEvents.SERVER_STARTING.register(Hooks::starting);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Hooks.stopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> Hooks.stopped());
        ServerTickEvents.END_SERVER_TICK.register(Hooks::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Hooks.joined(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Hooks.left(handler.getPlayer()));
        UseItemCallback.EVENT.register((player, level, hand) -> hand == InteractionHand.MAIN_HAND
                && player instanceof ServerPlayer sp && CustomItems.use(sp, player.getItemInHand(hand))
                ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (entity instanceof ServerPlayer victim && !Regions.canAttack(victim)) {
                return InteractionResult.FAIL;
            }
            if (player instanceof ServerPlayer sp) {
                CustomItems.hit(sp, entity);
            }
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> Regions.canBuild(level.dimension(), pos));
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                player.getItemInHand(hand).getItem() instanceof BlockItem
                        && !Regions.canBuild(level.dimension(), hit.getBlockPos().relative(hit.getDirection()))
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> Hooks.commands(dispatcher));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> hand == InteractionHand.MAIN_HAND
                && player instanceof ServerPlayer sp && Hooks.interact(sp, entity) ? InteractionResult.SUCCESS
                : InteractionResult.PASS);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                !Prompt.answer(sender.getUUID(), message.signedContent()));
    }

    /** The running server; throws before it starts. */
    public static MinecraftServer server() {
        return Hooks.server();
    }

    public static TickScheduler scheduler() {
        return Hooks.scheduler();
    }
}
