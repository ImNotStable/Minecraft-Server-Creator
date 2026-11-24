package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Items with their own behaviour, recognised by an id in their custom data: wands, keys, kit selectors. Register in
 * your mod's initializer; {@code Stdlib.init()} routes right-clicks and attacks here.
 *
 * <pre>{@code
 * CustomItems.register("dash", () -> ItemBuilder.of(Items.FEATHER).name(Component.literal("Dash")).build())
 *         .onUse(player -> player.push(player.getLookAngle().scale(1.5)))
 *         .cooldown(Duration.ofSeconds(3));
 * player.getInventory().add(CustomItems.create("dash"));
 * }</pre>
 */
public final class CustomItems {
    private static final String KEY = "msc_custom_item";
    private static final Map<String, Definition> ITEMS = new ConcurrentHashMap<>();

    private CustomItems() {}

    public static final class Definition {
        private final Supplier<ItemStack> factory;
        private Consumer<ServerPlayer> onUse;
        private BiConsumer<ServerPlayer, Entity> onHit;
        private Duration cooldown;
        private java.util.function.BiConsumer<ServerPlayer, Duration> tooSoon = (who, left) -> {};
        private final Cooldowns<UUID> cooldowns = new Cooldowns<>();

        private Definition(Supplier<ItemStack> factory) {
            this.factory = factory;
        }

        /** Right-click with it in the main hand (the item's own use is cancelled). */
        public Definition onUse(Consumer<ServerPlayer> action) {
            this.onUse = action;
            return this;
        }

        /** The holder attacks an entity with it (the attack still happens). */
        public Definition onHit(BiConsumer<ServerPlayer, Entity> action) {
            this.onHit = action;
            return this;
        }

        /** Time between uses per player; a use too soon does nothing. */
        public Definition cooldown(Duration time) {
            this.cooldown = time;
            return this;
        }

        /** Same, and {@code tooSoon} gets the player and the time left, to tell them your way. */
        public Definition cooldown(Duration time, java.util.function.BiConsumer<ServerPlayer, Duration> tooSoon) {
            this.cooldown = time;
            this.tooSoon = tooSoon;
            return this;
        }
    }

    public static Definition register(String id, Supplier<ItemStack> factory) {
        Definition d = new Definition(factory);
        ITEMS.put(id, d);
        return d;
    }

    public static ItemStack create(String id) {
        Definition d = ITEMS.get(id);
        if (d == null) {
            throw new IllegalArgumentException("No custom item '" + id + "' (registered: " + ITEMS.keySet() + ")");
        }
        return ItemBuilder.from(d.factory.get()).data(KEY, id).build();
    }

    public static Optional<String> id(ItemStack item) {
        return Optional.ofNullable(ItemBuilder.read(item, KEY));
    }

    public static boolean is(ItemStack item, String id) {
        return id.equals(ItemBuilder.read(item, KEY));
    }

    private static Optional<Definition> of(ItemStack item) {
        return id(item).map(ITEMS::get);
    }

    /** A right-click with {@code item}: runs its onUse; true when the click should be cancelled (it is ours). */
    public static boolean use(ServerPlayer player, ItemStack item) {
        Optional<Definition> d = of(item).filter(x -> x.onUse != null);
        d.ifPresent(x -> {
            if (x.cooldown == null || x.cooldowns.tryUse(player.getUUID(), x.cooldown)) {
                x.onUse.accept(player);
            } else {
                x.tooSoon.accept(player, x.cooldowns.remaining(player.getUUID()));
            }
        });
        return d.isPresent();
    }

    /** {@code player} attacks {@code target}: runs onHit of the main-hand item. */
    public static void hit(ServerPlayer player, Entity target) {
        of(player.getMainHandItem()).filter(d -> d.onHit != null).ifPresent(d -> d.onHit.accept(player, target));
    }
}
