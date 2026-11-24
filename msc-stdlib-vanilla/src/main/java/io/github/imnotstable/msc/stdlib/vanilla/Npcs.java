package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.npc.Dialogue;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Non-player characters: a mob (a villager by default) that stands still, cannot be hurt, never despawns, wears a name,
 * turns its head to the nearest player, and runs your code or a {@link Dialogue} when right-clicked. Dialogue choices
 * show as clickable chat lines ({@code /npcchoice}, registered by {@link #init}).
 *
 * <pre>{@code
 * Npcs.spawn(level, EntityTypes.VILLAGER, new Vec3(10.5, 64, 3.5), 90, Component.literal("Smith"),
 *         (npc, player) -> Npcs.talk(player, smithDialogue));
 * }</pre>
 */
public final class Npcs {
    private static final Map<UUID, BiConsumer<Entity, ServerPlayer>> CLICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Dialogue> TALKING = new ConcurrentHashMap<>();
    private static boolean looking;

    private Npcs() {}

    /** Spawn an NPC facing {@code yaw} degrees (0 south, 90 west); {@code onClick} runs on a right-click. */
    public static <T extends Entity> T spawn(ServerLevel level, EntityType<T> type, Vec3 at, float yaw, Component name,
                                             BiConsumer<Entity, ServerPlayer> onClick) {
        T e = type.create(level, EntitySpawnReason.COMMAND);
        if (e == null) {
            throw new IllegalStateException("Cannot create " + type);
        }
        e.setPos(at.x(), at.y(), at.z());
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setCustomName(name);
        e.setCustomNameVisible(true);
        e.setSilent(true);
        e.setPermanentlyInvulnerable(true);
        e.addTag("msc_npc");
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        level.addFreshEntity(e);
        if (onClick != null) {
            CLICKS.put(e.getUUID(), onClick);
        }
        startLooking();
        return e;
    }

    /** Forget an NPC's click and remove it. */
    public static void remove(Entity npc) {
        CLICKS.remove(npc.getUUID());
        npc.discard();
    }

    /** Called from the loader's entity interaction event; true when an NPC handled the click. */
    static boolean interact(ServerPlayer player, Entity target) {
        BiConsumer<Entity, ServerPlayer> click = CLICKS.get(target.getUUID());
        if (click == null) {
            return false;
        }
        click.accept(target, player);
        return true;
    }

    /** Start a dialogue with a player: shows its first page. */
    public static void talk(ServerPlayer player, Dialogue dialogue) {
        TALKING.put(player.getUUID(), dialogue);
        dialogue.open(player.getUUID());
        show(player, dialogue);
    }

    /** The player picked a choice (from the clickable line). */
    static void choose(ServerPlayer player, int index) {
        Dialogue d = TALKING.get(player.getUUID());
        if (d == null) {
            return;
        }
        if (d.choose(player.getUUID(), index).isPresent()) {
            show(player, d);
        } else {
            TALKING.remove(player.getUUID());
        }
    }

    private static void show(ServerPlayer player, Dialogue d) {
        d.current(player.getUUID()).ifPresent(page -> {
            for (String line : Dialogue.fill(page.lines(), player.getName().getString())) {
                player.sendSystemMessage(Component.literal(line));
            }
            List<Dialogue.Choice> choices = d.choices(player.getUUID());
            MutableComponent row = Component.empty();
            for (int i = 0; i < choices.size(); i++) {
                int index = i;
                row.append(Component.literal(choices.get(i).label()).withStyle(s -> s
                        .withClickEvent(new ClickEvent.RunCommand("/npcchoice " + index))));
                row.append(Component.literal("  "));
            }
            if (!choices.isEmpty()) {
                player.sendSystemMessage(row);
            }
        });
    }

    /** Registers {@code /npcchoice}, the command dialogue lines click. Call once, after {@code Stdlib.init()}. */
    public static void init() {
        Cmd.register(io.github.imnotstable.msc.stdlib.cmd.CommandTree.create("npcchoice").playerOnly()
                .executes(c -> choose(Cmd.player(c), c.argInt(0))));
    }

    private static synchronized void startLooking() {
        if (looking) {
            return;
        }
        looking = true;
        Hooks.scheduler().repeat(10, 10, () -> {
            if (CLICKS.isEmpty()) {
                return;
            }
            for (ServerLevel level : Hooks.server().getAllLevels()) {
                for (UUID id : CLICKS.keySet()) {
                    Entity npc = level.getEntity(id);
                    if (npc == null) {
                        continue;
                    }
                    ServerPlayer near = null;
                    double best = 64;
                    for (ServerPlayer p : level.players()) {
                        double d = p.distanceToSqr(npc);
                        if (d < best) {
                            best = d;
                            near = p;
                        }
                    }
                    if (near != null) {
                        double dx = near.getX() - npc.getX();
                        double dz = near.getZ() - npc.getZ();
                        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                        npc.setYHeadRot(yaw);
                    }
                }
            }
        });
    }
}
