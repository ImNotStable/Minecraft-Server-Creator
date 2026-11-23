package io.github.imnotstable.msc.stdlib.neoforge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Call {@link #init} first in your mod's constructor: it hooks the scheduler, player data, custom items, regions and
 * prompts into NeoForge's game events. Safe to call from several mods.
 */
public final class Stdlib {
    private static boolean hooked;

    private Stdlib() {}

    public static synchronized void init() {
        if (hooked) {
            return;
        }
        hooked = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ServerStartingEvent.class, e -> Hooks.starting(e.getServer()));
        bus.addListener(ServerStoppingEvent.class, e -> Hooks.stopping());
        bus.addListener(ServerStoppedEvent.class, e -> Hooks.stopped());
        bus.addListener(ServerTickEvent.Post.class, e -> Hooks.tick(e.getServer()));
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                Hooks.joined(p);
            }
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                Hooks.left(p);
            }
        });
        bus.addListener(PlayerInteractEvent.RightClickItem.class, e -> {
            if (e.getHand() == InteractionHand.MAIN_HAND && e.getEntity() instanceof ServerPlayer p
                    && CustomItems.use(p, e.getItemStack())) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.FAIL);
            }
        });
        bus.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class, e -> Hooks.commands(e.getDispatcher()));
        bus.addListener(PlayerInteractEvent.EntityInteract.class, e -> {
            if (e.getHand() == InteractionHand.MAIN_HAND && e.getEntity() instanceof ServerPlayer p
                    && Hooks.interact(p, e.getTarget())) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        bus.addListener(AttackEntityEvent.class, e -> {
            if (e.getTarget() instanceof ServerPlayer victim && !Regions.canAttack(victim)) {
                e.setCanceled(true);
                return;
            }
            if (e.getEntity() instanceof ServerPlayer p) {
                CustomItems.hit(p, e.getTarget());
            }
        });
        bus.addListener(BreakBlockEvent.class, e -> {
            if (e.getLevel() instanceof Level level && !Regions.canBuild(level.dimension(), e.getPos())) {
                e.setCanceled(true);
            }
        });
        bus.addListener(BlockEvent.EntityPlaceEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer && e.getLevel() instanceof Level level
                    && !Regions.canBuild(level.dimension(), e.getPos())) {
                e.setCanceled(true);
            }
        });
        bus.addListener(ServerChatEvent.class, e -> {
            if (Prompt.answer(e.getPlayer().getUUID(), e.getRawText())) {
                e.setCanceled(true);
            }
        });
    }

    /** The running server; throws before it starts. */
    public static MinecraftServer server() {
        return Hooks.server();
    }

    public static TickScheduler scheduler() {
        return Hooks.scheduler();
    }
}
