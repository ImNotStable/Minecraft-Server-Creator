package io.github.imnotstable.msc.stdlib.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Items with their own behaviour, recognised by an id stored on the item (renaming or stacking does not lose it):
 * wands, keys, grenades, menu openers, kit selectors.
 *
 * <pre>{@code
 * CustomItems.register("lightning_wand",
 *         () -> ItemBuilder.of(Material.BLAZE_ROD).name("<yellow>Lightning Wand").glow().build())
 *     .onUse(e -> e.getPlayer().getWorld().strikeLightning(e.getPlayer().getTargetBlockExact(50).getLocation()))
 *     .cooldown(Duration.ofSeconds(3))
 *     .onHit(e -> e.setDamage(e.getDamage() * 2));
 * player.getInventory().addItem(CustomItems.create("lightning_wand"));
 * }</pre>
 */
public final class CustomItems {
    private static final Map<String, Definition> ITEMS = new ConcurrentHashMap<>();
    private static boolean listening;

    private CustomItems() {}

    /** One custom item's behaviour; chain the handlers you need. */
    public static final class Definition {
        private final String id;
        private final Supplier<ItemStack> factory;
        private Consumer<PlayerInteractEvent> onUse;
        private Consumer<PlayerInteractEvent> onLeftClick;
        private Consumer<EntityDamageByEntityEvent> onHit;
        private Consumer<PlayerItemConsumeEvent> onConsume;
        private boolean undroppable;
        private boolean unplaceable = true;
        private java.time.Duration cooldown;
        private java.util.function.BiConsumer<Player, java.time.Duration> tooSoon = (who, left) -> {};
        private final io.github.imnotstable.msc.stdlib.time.Cooldowns<java.util.UUID> cooldowns =
                new io.github.imnotstable.msc.stdlib.time.Cooldowns<>();

        private Definition(String id, Supplier<ItemStack> factory) {
            this.id = id;
            this.factory = factory;
        }

        /** Right-click in air or on a block (cancelled for you, so blocks are not placed and doors not opened). */
        public Definition onUse(Consumer<PlayerInteractEvent> action) {
            this.onUse = action;
            return this;
        }

        public Definition onLeftClick(Consumer<PlayerInteractEvent> action) {
            this.onLeftClick = action;
            return this;
        }

        /** The holder hits an entity with it. */
        public Definition onHit(Consumer<EntityDamageByEntityEvent> action) {
            this.onHit = action;
            return this;
        }

        /** Eaten or drunk. */
        public Definition onConsume(Consumer<PlayerItemConsumeEvent> action) {
            this.onConsume = action;
            return this;
        }

        /** Time between uses per player; a use too soon does nothing. */
        public Definition cooldown(java.time.Duration time) {
            this.cooldown = time;
            return this;
        }

        /** Same, and {@code tooSoon} gets the player and the time left, to tell them your way. */
        public Definition cooldown(java.time.Duration time, java.util.function.BiConsumer<Player, java.time.Duration> tooSoon) {
            this.cooldown = time;
            this.tooSoon = tooSoon;
            return this;
        }

        /** Cannot be dropped (menu items, compasses). */
        public Definition undroppable() {
            this.undroppable = true;
            return this;
        }

        /** Block items stay placeable when you say so (placing is refused by default). */
        public Definition placeable() {
            this.unplaceable = false;
            return this;
        }

        public String id() {
            return id;
        }
    }

    public static Definition register(String id, Supplier<ItemStack> factory) {
        listen();
        Definition d = new Definition(id, factory);
        ITEMS.put(id, d);
        return d;
    }

    /** A new stack of the item, tagged with its id. */
    public static ItemStack create(String id) {
        Definition d = ITEMS.get(id);
        if (d == null) {
            throw new IllegalArgumentException("No custom item '" + id + "' (registered: " + ITEMS.keySet() + ")");
        }
        ItemStack item = d.factory.get();
        item.editMeta(m -> m.getPersistentDataContainer().set(key(), PersistentDataType.STRING, id));
        return item;
    }

    /** The custom id of {@code item}, if it is one. */
    public static Optional<String> id(ItemStack item) {
        return Optional.ofNullable(ItemBuilder.read(item, key(), PersistentDataType.STRING));
    }

    public static boolean is(ItemStack item, String id) {
        return id(item).filter(id::equals).isPresent();
    }

    private static NamespacedKey key() {
        return new NamespacedKey(Stdlib.plugin(), "custom_item");
    }

    private static Optional<Definition> of(ItemStack item) {
        return id(item).map(ITEMS::get);
    }

    private static boolean ready(Definition d, Player p) {
        if (d.cooldown == null || d.cooldowns.tryUse(p.getUniqueId(), d.cooldown)) {
            return true;
        }
        d.tooSoon.accept(p, d.cooldowns.remaining(p.getUniqueId()));
        return false;
    }

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        var plugin = Stdlib.plugin();
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void interact(PlayerInteractEvent e) {
                if (e.getHand() != EquipmentSlot.HAND || e.getItem() == null) {
                    return;
                }
                of(e.getItem()).ifPresent(d -> {
                    boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
                    boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
                    if (right && d.onUse != null) {
                        e.setCancelled(true);
                        if (ready(d, e.getPlayer())) {
                            d.onUse.accept(e);
                        }
                    } else if (left && d.onLeftClick != null && ready(d, e.getPlayer())) {
                        d.onLeftClick.accept(e);
                    }
                });
            }

            @EventHandler(ignoreCancelled = true)
            public void hit(EntityDamageByEntityEvent e) {
                if (e.getDamager() instanceof Player p) {
                    of(p.getInventory().getItemInMainHand()).filter(d -> d.onHit != null).ifPresent(d -> d.onHit.accept(e));
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void consume(PlayerItemConsumeEvent e) {
                of(e.getItem()).filter(d -> d.onConsume != null).ifPresent(d -> d.onConsume.accept(e));
            }

            @EventHandler(ignoreCancelled = true)
            public void drop(PlayerDropItemEvent e) {
                of(e.getItemDrop().getItemStack()).filter(d -> d.undroppable).ifPresent(d -> e.setCancelled(true));
            }

            @EventHandler(ignoreCancelled = true)
            public void place(BlockPlaceEvent e) {
                of(e.getItemInHand()).filter(d -> d.unplaceable).ifPresent(d -> e.setCancelled(true));
            }
        }, plugin);
    }

    static synchronized void reset() {
        listening = false;
        ITEMS.clear();
    }
}
