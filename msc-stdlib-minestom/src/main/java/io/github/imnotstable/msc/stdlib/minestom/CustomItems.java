package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.ui.Feedback;
import io.github.imnotstable.msc.stdlib.util.Format;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Items with their own behaviour, recognised by an id tag: wands, keys, kit selectors.
 *
 * <pre>{@code
 * CustomItems.register("dash", () -> ItemBuilder.of(Material.FEATHER).name("<aqua>Dash").build())
 *         .onUse(e -> e.getPlayer().setVelocity(e.getPlayer().getPosition().direction().mul(30)))
 *         .cooldown(Duration.ofSeconds(3));
 * player.getInventory().addItemStack(CustomItems.create("dash"));
 * }</pre>
 */
public final class CustomItems {
    private static final Tag<String> ID = Tag.String("msc_custom_item");
    private static final Map<String, Definition> ITEMS = new ConcurrentHashMap<>();
    private static boolean listening;

    private CustomItems() {}

    public static final class Definition {
        private final Supplier<ItemStack> factory;
        private Consumer<PlayerUseItemEvent> onUse;
        private Consumer<EntityAttackEvent> onHit;
        private boolean undroppable;
        private Duration cooldown;
        private final Cooldowns<UUID> cooldowns = new Cooldowns<>();

        private Definition(Supplier<ItemStack> factory) {
            this.factory = factory;
        }

        /** Right-click with it. */
        public Definition onUse(Consumer<PlayerUseItemEvent> action) {
            this.onUse = action;
            return this;
        }

        /** The holder attacks an entity with it in the main hand. */
        public Definition onHit(Consumer<EntityAttackEvent> action) {
            this.onHit = action;
            return this;
        }

        public Definition cooldown(Duration time) {
            this.cooldown = time;
            return this;
        }

        public Definition undroppable() {
            this.undroppable = true;
            return this;
        }
    }

    public static Definition register(String id, Supplier<ItemStack> factory) {
        listen();
        Definition d = new Definition(factory);
        ITEMS.put(id, d);
        return d;
    }

    public static ItemStack create(String id) {
        Definition d = ITEMS.get(id);
        if (d == null) {
            throw new IllegalArgumentException("No custom item '" + id + "' (registered: " + ITEMS.keySet() + ")");
        }
        return d.factory.get().withTag(ID, id);
    }

    public static Optional<String> id(ItemStack item) {
        return Optional.ofNullable(item.getTag(ID));
    }

    public static boolean is(ItemStack item, String id) {
        return id.equals(item.getTag(ID));
    }

    private static Optional<Definition> of(ItemStack item) {
        return id(item).map(ITEMS::get);
    }

    private static boolean ready(Definition d, Player p) {
        if (d.cooldown == null || d.cooldowns.tryUse(p.getUuid(), d.cooldown)) {
            return true;
        }
        Feedback.actionBar(p, "<red>Wait <time>", "time", Format.duration(d.cooldowns.remaining(p.getUuid())));
        return false;
    }

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        Stdlib.events().addListener(PlayerUseItemEvent.class, e -> of(e.getItemStack()).filter(d -> d.onUse != null)
                .ifPresent(d -> {
                    e.setCancelled(true);
                    if (ready(d, e.getPlayer())) {
                        d.onUse.accept(e);
                    }
                }));
        Stdlib.events().addListener(EntityAttackEvent.class, e -> {
            if (e.getEntity() instanceof Player p) {
                of(p.getItemInMainHand()).filter(d -> d.onHit != null).ifPresent(d -> d.onHit.accept(e));
            }
        });
        Stdlib.events().addListener(ItemDropEvent.class, e -> of(e.getItemStack()).filter(d -> d.undroppable)
                .ifPresent(d -> e.setCancelled(true)));
    }

    static synchronized void reset() {
        listening = false;
        ITEMS.clear();
    }
}
