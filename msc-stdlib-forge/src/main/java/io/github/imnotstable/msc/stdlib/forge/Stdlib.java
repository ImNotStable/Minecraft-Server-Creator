package io.github.imnotstable.msc.stdlib.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.function.Predicate;

/**
 * Call {@link #init} first in your mod's constructor: it hooks the scheduler, player data, custom items, regions and
 * prompts into Forge's events. Safe to call from several mods.
 */
public final class Stdlib {
    private static boolean hooked;

    private Stdlib() {}

    public static synchronized void init() {
        if (hooked) {
            return;
        }
        hooked = true;
        ServerStartingEvent.BUS.addListener(e -> Hooks.starting(e.getServer()));
        ServerStoppingEvent.BUS.addListener(e -> Hooks.stopping());
        ServerStoppedEvent.BUS.addListener(e -> Hooks.stopped());
        TickEvent.ServerTickEvent.Post.BUS.addListener(e -> Hooks.tick(e.server()));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                Hooks.joined(p);
            }
        });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                Hooks.left(p);
            }
        });
        // A predicate listener cancels the event by returning true.
        PlayerInteractEvent.RightClickItem.BUS.addListener((Predicate<PlayerInteractEvent.RightClickItem>) e -> e.getHand() == InteractionHand.MAIN_HAND
                && e.getEntity() instanceof ServerPlayer p && CustomItems.use(p, e.getItemStack()));
        AttackEntityEvent.BUS.addListener((Predicate<AttackEntityEvent>) e -> {
            if (e.getTarget() instanceof ServerPlayer victim && !Regions.canAttack(victim)) {
                return true;
            }
            if (e.getEntity() instanceof ServerPlayer p) {
                CustomItems.hit(p, e.getTarget());
            }
            return false;
        });
        BlockEvent.BreakEvent.BUS.addListener((Predicate<BlockEvent.BreakEvent>) e -> e.getLevel() instanceof Level level
                && !Regions.canBuild(level.dimension(), e.getPos()));
        BlockEvent.EntityPlaceEvent.BUS.addListener((Predicate<BlockEvent.EntityPlaceEvent>) e -> e.getEntity() instanceof ServerPlayer
                && e.getLevel() instanceof Level level && !Regions.canBuild(level.dimension(), e.getPos()));
        ServerChatEvent.BUS.addListener((Predicate<ServerChatEvent>) e -> Prompt.answer(e.getPlayer().getUUID(), e.getRawText()));
    }

    /** The running server; throws before it starts. */
    public static MinecraftServer server() {
        return Hooks.server();
    }

    public static TickScheduler scheduler() {
        return Hooks.scheduler();
    }
}
