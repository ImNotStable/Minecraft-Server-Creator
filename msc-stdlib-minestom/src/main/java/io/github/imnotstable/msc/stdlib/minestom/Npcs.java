package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.npc.Dialogue;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Non-player characters: an entity of any type (a villager by default) that stands still, wears a name, turns its head
 * to the nearest player and runs your code or a {@link Dialogue} when right-clicked; dialogue choices are clickable
 * chat lines. Minestom entities have no AI unless you give them some, and are never saved: spawn them at start.
 *
 * <pre>{@code
 * Npcs.spawn(instance, EntityType.VILLAGER, new Pos(10.5, 64, 3.5, 90, 0), "<gold>Smith",
 *         (npc, player) -> Npcs.talk(player, smithDialogue));
 * }</pre>
 */
public final class Npcs {
    private static final Map<UUID, BiConsumer<Entity, Player>> CLICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Entity> NPCS = new ConcurrentHashMap<>();
    private static final Map<UUID, Dialogue> TALKING = new ConcurrentHashMap<>();
    private static boolean started;

    private Npcs() {}

    public static Entity spawn(Instance instance, EntityType type, Pos at, String miniMessageName,
                               BiConsumer<Entity, Player> onClick) {
        start();
        Entity e = new Entity(type);
        e.set(net.minestom.server.component.DataComponents.CUSTOM_NAME, Text.mm(miniMessageName));
        e.editEntityMeta(net.minestom.server.entity.metadata.EntityMeta.class, meta -> {
            meta.setCustomNameVisible(true);
            meta.setSilent(true);
        });
        e.setInstance(instance, at);
        NPCS.put(e.getUuid(), e);
        if (onClick != null) {
            CLICKS.put(e.getUuid(), onClick);
        }
        return e;
    }

    public static void remove(Entity npc) {
        CLICKS.remove(npc.getUuid());
        NPCS.remove(npc.getUuid());
        npc.remove();
    }

    public static void talk(Player player, Dialogue dialogue) {
        TALKING.put(player.getUuid(), dialogue);
        dialogue.open(player.getUuid());
        show(player, dialogue);
    }

    private static void choose(Player player, int index) {
        Dialogue d = TALKING.get(player.getUuid());
        if (d == null) {
            return;
        }
        if (d.choose(player.getUuid(), index).isPresent()) {
            show(player, d);
        } else {
            TALKING.remove(player.getUuid());
        }
    }

    private static void show(Player player, Dialogue d) {
        d.current(player.getUuid()).ifPresent(page -> {
            for (String line : Dialogue.fill(page.lines(), player.getUsername())) {
                player.sendMessage(Text.mm(line));
            }
            List<Dialogue.Choice> choices = d.choices(player.getUuid());
            Component row = Component.empty();
            for (int i = 0; i < choices.size(); i++) {
                row = row.append(Text.mm(choices.get(i).label()).clickEvent(ClickEvent.runCommand("/npcchoice " + i))).append(Component.text("  "));
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
        Stdlib.events().addListener(PlayerEntityInteractEvent.class, e -> {
            if (e.getHand() != PlayerHand.MAIN) {
                return;
            }
            BiConsumer<Entity, Player> click = CLICKS.get(e.getTarget().getUuid());
            if (click != null) {
                click.accept(e.getTarget(), e.getPlayer());
            }
        });
        Cmd.register(io.github.imnotstable.msc.stdlib.cmd.CommandTree.create("npcchoice").playerOnly()
                .executes(c -> choose(Cmd.player(c), c.argInt(0))));
        Stdlib.scheduler().repeat(10, 10, () -> {
            for (Entity npc : NPCS.values()) {
                if (npc.isRemoved() || npc.getInstance() == null) {
                    continue;
                }
                Player near = null;
                double best = 64;
                for (Player p : npc.getInstance().getPlayers()) {
                    double d = p.getPosition().distanceSquared(npc.getPosition());
                    if (d < best) {
                        best = d;
                        near = p;
                    }
                }
                if (near != null) {
                    npc.lookAt(near);
                }
            }
        });
    }
}
