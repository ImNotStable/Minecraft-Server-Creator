package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.npc.Dialogue;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Non-player characters: any mob (a villager by default) that stands still, cannot be hurt, never despawns, wears a
 * name, turns its head to the nearest player, and runs your code or a {@link Dialogue} when right-clicked. Dialogue
 * choices are clickable chat lines.
 *
 * <pre>{@code
 * Npcs.spawn(new Location(world, 10.5, 64, 3.5, 90, 0), EntityType.VILLAGER, "<gold>Smith",
 *         (npc, player) -> Npcs.talk(player, smithDialogue));
 * }</pre>
 * Clicks are kept in memory: spawn NPCs in {@code onEnable} (or remove old ones first: they carry the
 * {@code msc_npc} key).
 */
public final class Npcs {
    private static final Map<UUID, BiConsumer<Entity, Player>> CLICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Dialogue> TALKING = new ConcurrentHashMap<>();
    private static boolean started;

    private Npcs() {}

    public static Entity spawn(Location at, EntityType type, String miniMessageName, BiConsumer<Entity, Player> onClick) {
        start();
        Entity e = at.getWorld().spawnEntity(at, type);
        e.customName(Text.mm(miniMessageName));
        e.setCustomNameVisible(true);
        e.setSilent(true);
        e.setInvulnerable(true);
        e.setPersistent(true);
        e.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(Stdlib.plugin(), "msc_npc"),
                PersistentDataType.BYTE, (byte) 1);
        if (e instanceof Mob mob) {
            mob.setAware(false);
        }
        if (e instanceof LivingEntity living) {
            living.setCollidable(false);
        }
        if (onClick != null) {
            CLICKS.put(e.getUniqueId(), onClick);
        }
        return e;
    }

    public static void remove(Entity npc) {
        CLICKS.remove(npc.getUniqueId());
        npc.remove();
    }

    /** Remove every NPC this plugin spawned before (left over from the last run). */
    public static void removeAll(org.bukkit.World world) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(Stdlib.plugin(), "msc_npc");
        for (Entity e : world.getEntities()) {
            if (e.getPersistentDataContainer().has(key)) {
                remove(e);
            }
        }
    }

    public static void talk(Player player, Dialogue dialogue) {
        TALKING.put(player.getUniqueId(), dialogue);
        dialogue.open(player.getUniqueId());
        show(player, dialogue);
    }

    private static void choose(Player player, int index) {
        Dialogue d = TALKING.get(player.getUniqueId());
        if (d == null) {
            return;
        }
        if (d.choose(player.getUniqueId(), index).isPresent()) {
            show(player, d);
        } else {
            TALKING.remove(player.getUniqueId());
        }
    }

    private static void show(Player player, Dialogue d) {
        d.current(player.getUniqueId()).ifPresent(page -> {
            for (String line : Dialogue.fill(page.lines(), player.getName())) {
                player.sendMessage(Text.mm(line));
            }
            List<Dialogue.Choice> choices = d.choices(player.getUniqueId());
            Component row = Component.empty();
            for (int i = 0; i < choices.size(); i++) {
                int index = i;
                row = row.append(Text.mm(choices.get(i).label()).clickEvent(ClickEvent.callback(a -> {
                            if (a instanceof Player p) {
                                choose(p, index);
                            }
                        }))).append(Component.text("  "));
            }
            if (!choices.isEmpty()) {
                player.sendMessage(row);
            }
        });
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Stdlib.plugin().getServer().getPluginManager().registerEvents(new Clicks(), Stdlib.plugin());
        Stdlib.scheduler().repeat(10, 10, () -> {
            for (UUID id : CLICKS.keySet()) {
                Entity npc = Stdlib.plugin().getServer().getEntity(id);
                if (npc == null) {
                    continue;
                }
                Player near = null;
                double best = 64;
                for (Player p : npc.getWorld().getPlayers()) {
                    double d = p.getLocation().distanceSquared(npc.getLocation());
                    if (d < best) {
                        best = d;
                        near = p;
                    }
                }
                if (near != null) {
                    double dx = near.getX() - npc.getX();
                    double dz = near.getZ() - npc.getZ();
                    npc.setRotation((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90), 0);
                }
            }
        });
    }

    static final class Clicks implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onClick(PlayerInteractEntityEvent e) {
            if (e.getHand() != EquipmentSlot.HAND) {
                return;
            }
            BiConsumer<Entity, Player> click = CLICKS.get(e.getRightClicked().getUniqueId());
            if (click != null) {
                e.setCancelled(true);
                click.accept(e.getRightClicked(), e.getPlayer());
            }
        }
    }
}
