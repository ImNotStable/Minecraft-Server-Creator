package io.github.imnotstable.msc.stdlib.fabric;

import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.util.Format;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * {@code onInitializeServer}.
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
    private static boolean listening;

    private CustomItems() {}

    public static final class Definition {
        private final Supplier<ItemStack> factory;
        private Consumer<ServerPlayer> onUse;
        private BiConsumer<ServerPlayer, Entity> onHit;
        private Duration cooldown;
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

        public Definition cooldown(Duration time) {
            this.cooldown = time;
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

    private static boolean ready(Definition d, ServerPlayer p) {
        if (d.cooldown == null || d.cooldowns.tryUse(p.getUUID(), d.cooldown)) {
            return true;
        }
        Feedback.actionBar(p, "Wait " + Format.duration(d.cooldowns.remaining(p.getUUID())));
        return false;
    }

    /** Runs a use as a right-click would (tests, or your own triggers); true when the item is a custom one with onUse. */
    public static boolean use(ServerPlayer player, ItemStack item) {
        Optional<Definition> d = of(item).filter(x -> x.onUse != null);
        d.ifPresent(x -> {
            if (ready(x, player)) {
                x.onUse.accept(player);
            }
        });
        return d.isPresent();
    }

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp
                    && use(sp, player.getItemInHand(hand))) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp) {
                of(player.getMainHandItem()).filter(d -> d.onHit != null).ifPresent(d -> d.onHit.accept(sp, entity));
            }
            return InteractionResult.PASS;
        });
    }
}
