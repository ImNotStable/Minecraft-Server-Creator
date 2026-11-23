package io.github.imnotstable.msc.stdlib.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Loot as the game rolls it: pools, each rolled a number of times (more with luck), each roll picking one entry by
 * weight, with a count between a minimum and a maximum; entries and pools can have conditions. {@code T} is whatever
 * the platform's item is: an item id, an {@code ItemStack} factory, a reward.
 *
 * <pre>{@code
 * LootTable<String> chest = LootTable.<String>builder()
 *         .pool(p -> p.rolls(2, 4)
 *                 .entry("minecraft:bread", 10, 1, 3)
 *                 .entry("minecraft:iron_ingot", 5, 1, 2)
 *                 .entry("minecraft:diamond", 1, 1, 1))
 *         .pool(p -> p.rolls(1, 1).when(ctx -> ctx.luck() > 0).entry("minecraft:emerald", 1, 1, 1))
 *         .build();
 * for (LootTable.Drop<String> d : chest.roll(random, LootTable.Context.of(player, 0))) give(d.item(), d.count());
 * String json = LootTable.vanillaJson(chest, "minecraft:chest");   // the same table as a data pack loot table
 * }</pre>
 */
public final class LootTable<T> {
    /** Who and what a roll is for: the player (or null), their luck, and anything else conditions read. */
    public record Context(UUID player, double luck, Map<String, Object> data) {
        public static Context of(UUID player, double luck) {
            return new Context(player, luck, Map.of());
        }

        public static Context none() {
            return new Context(null, 0, Map.of());
        }
    }

    /** One item that came out, with how many. */
    public record Drop<T>(T item, int count) {
    }

    /** One choice in a pool: nothing ({@code item} null) is a valid choice that leaves the roll empty. */
    public record Entry<T>(T item, int weight, int min, int max, Predicate<Context> when) {
        public Entry {
            if (weight < 1 || min < 0 || max < min) {
                throw new IllegalArgumentException("entry needs weight >= 1 and 0 <= min <= max");
            }
        }
    }

    /** A pool: rolled {@code minRolls..maxRolls} times plus {@code bonusPerLuck} per point of luck. */
    public record Pool<T>(int minRolls, int maxRolls, double bonusPerLuck, List<Entry<T>> entries,
                          List<Predicate<Context>> conditions) {
    }

    private final List<Pool<T>> pools;

    private LootTable(List<Pool<T>> pools) {
        this.pools = List.copyOf(pools);
    }

    public List<Pool<T>> pools() {
        return pools;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Roll the whole table; equal items from separate rolls stay separate drops. */
    public List<Drop<T>> roll(RandomGenerator random, Context ctx) {
        List<Drop<T>> out = new ArrayList<>();
        for (Pool<T> pool : pools) {
            if (!pool.conditions().stream().allMatch(c -> c.test(ctx))) {
                continue;
            }
            double extra = pool.bonusPerLuck() * ctx.luck();
            int rolls = pool.minRolls() + (pool.maxRolls() > pool.minRolls()
                    ? random.nextInt(pool.maxRolls() - pool.minRolls() + 1) : 0);
            rolls += (int) Math.floor(extra) + (random.nextDouble() < extra - Math.floor(extra) ? 1 : 0);
            for (int r = 0; r < rolls; r++) {
                List<Entry<T>> open = pool.entries().stream().filter(e -> e.when() == null || e.when().test(ctx)).toList();
                int total = open.stream().mapToInt(Entry::weight).sum();
                if (total == 0) {
                    break;
                }
                int pick = random.nextInt(total);
                for (Entry<T> e : open) {
                    pick -= e.weight();
                    if (pick < 0) {
                        int count = e.min() + (e.max() > e.min() ? random.nextInt(e.max() - e.min() + 1) : 0);
                        if (e.item() != null && count > 0) {
                            out.add(new Drop<>(e.item(), count));
                        }
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * The table as a data pack loot table ({@code data/<ns>/loot_table/<name>.json}), for chests and mobs the game fills
     * itself; item ids as entries. Conditions written in code cannot be carried over and are left out.
     */
    public static String vanillaJson(LootTable<String> table, String type) {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        JsonArray pools = new JsonArray();
        for (Pool<String> p : table.pools()) {
            JsonObject pool = new JsonObject();
            JsonObject rolls = new JsonObject();
            rolls.addProperty("type", "minecraft:uniform");
            rolls.addProperty("min", p.minRolls());
            rolls.addProperty("max", p.maxRolls());
            pool.add("rolls", rolls);
            pool.addProperty("bonus_rolls", p.bonusPerLuck());
            JsonArray entries = new JsonArray();
            for (Entry<String> e : p.entries()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("weight", e.weight());
                if (e.item() == null) {
                    entry.addProperty("type", "minecraft:empty");
                } else {
                    entry.addProperty("type", "minecraft:item");
                    entry.addProperty("name", e.item());
                    if (e.min() != 1 || e.max() != 1) {
                        JsonObject count = new JsonObject();
                        count.addProperty("type", "minecraft:uniform");
                        count.addProperty("min", e.min());
                        count.addProperty("max", e.max());
                        JsonObject set = new JsonObject();
                        set.addProperty("function", "minecraft:set_count");
                        set.add("count", count);
                        JsonArray functions = new JsonArray();
                        functions.add(set);
                        entry.add("functions", functions);
                    }
                }
                entries.add(entry);
            }
            pool.add("entries", entries);
            pools.add(pool);
        }
        root.add("pools", pools);
        return root.toString();
    }

    public static final class Builder<T> {
        private final List<Pool<T>> pools = new ArrayList<>();

        public Builder<T> pool(java.util.function.Consumer<PoolBuilder<T>> spec) {
            PoolBuilder<T> b = new PoolBuilder<>();
            spec.accept(b);
            pools.add(new Pool<>(b.min, b.max, b.bonus, List.copyOf(b.entries), List.copyOf(b.conditions)));
            return this;
        }

        public LootTable<T> build() {
            return new LootTable<>(pools);
        }
    }

    public static final class PoolBuilder<T> {
        private int min = 1;
        private int max = 1;
        private double bonus;
        private final List<Entry<T>> entries = new ArrayList<>();
        private final List<Predicate<Context>> conditions = new ArrayList<>();

        public PoolBuilder<T> rolls(int min, int max) {
            if (min < 0 || max < min) {
                throw new IllegalArgumentException("rolls need 0 <= min <= max");
            }
            this.min = min;
            this.max = max;
            return this;
        }

        /** Extra rolls per point of luck (a fraction rolls once with that chance). */
        public PoolBuilder<T> bonusPerLuck(double rolls) {
            this.bonus = rolls;
            return this;
        }

        public PoolBuilder<T> entry(T item, int weight, int min, int max) {
            entries.add(new Entry<>(item, weight, min, max, null));
            return this;
        }

        public PoolBuilder<T> entry(T item, int weight, int min, int max, Predicate<Context> when) {
            entries.add(new Entry<>(item, weight, min, max, when));
            return this;
        }

        /** A chance of nothing: weight against the other entries. */
        public PoolBuilder<T> nothing(int weight) {
            entries.add(new Entry<>(null, weight, 0, 0, null));
            return this;
        }

        /** Roll this pool only when {@code condition} holds. */
        public PoolBuilder<T> when(Predicate<Context> condition) {
            conditions.add(condition);
            return this;
        }
    }
}
