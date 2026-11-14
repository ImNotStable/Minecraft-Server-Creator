package {{packageName}};

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * Everything in config.yml, checked once on start. A missing key takes its default; a value of the wrong type, a bad
 * value or an unknown key fails with a message naming the key. To add a setting, add its key to {@link #KEYS} (or
 * {@link #STAGE_KEYS}), read it here, give it a default in config.yml and a bad-value case in GameConfigTest.
 */
public record GameConfig(String world, GameMode gameMode, Difficulty difficulty, long time, boolean naturalSpawns,
                         boolean keepInventory, Kit kit, List<Stage> stages) {

    /** The top-level keys config.yml may hold. Any other key fails, so a typo cannot silently fall back to a default. */
    static final List<String> KEYS = List.of(
            "world", "game-mode", "difficulty", "time", "natural-spawns", "keep-inventory", "kit", "stages");
    /** The keys a stage may hold. */
    static final List<String> STAGE_KEYS = List.of("name", "spawn", "goal");

    static GameConfig from(ConfigurationSection c) {
        unknownKeys(c.getKeys(false), KEYS, "");
        return new GameConfig(
                string(c, "world", "world"),
                parse(GameMode.class, "game-mode", string(c, "game-mode", "adventure")),
                parse(Difficulty.class, "difficulty", string(c, "difficulty", "normal")),
                wholeNumber(c, "time", 6000),
                bool(c, "natural-spawns", false),
                bool(c, "keep-inventory", true),
                Kit.parse(strings(list(c, "kit"), "kit")),
                stages(list(c, "stages")));
    }

    private static List<Stage> stages(List<?> raw) {
        List<Stage> stages = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String where = "stages[" + i + "]";
            if (!(raw.get(i) instanceof Map<?, ?> s)) {
                throw new IllegalArgumentException(where + " needs {name: ..., spawn: [x, y, z]}, got " + raw.get(i));
            }
            if (!(s.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(where + " needs a name");
            }
            where += " (" + name + ")";
            unknownKeys(s.keySet(), STAGE_KEYS, where + ": ");
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("stages[" + i + "]: duplicate stage name " + name);
            }
            Object spawn = s.get("spawn");
            if (spawn == null) {
                throw new IllegalArgumentException(where + " needs a spawn");
            }
            Object goal = s.get("goal");
            stages.add(new Stage(name, Spot.parse(numbers(spawn, where + ".spawn", "[x, y, z]"), where + ".spawn"),
                    goal == null ? null : Box.parse(numbers(goal, where + ".goal", "[x1, y1, z1, x2, y2, z2]"),
                            where + ".goal")));
        }
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("stages: add at least one stage");
        }
        return List.copyOf(stages);
    }

    private static void unknownKeys(Set<?> keys, List<String> known, String where) {
        for (Object key : keys) {
            if (!known.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(where + "unknown key " + key + " (known: " + String.join(", ", known) + ")");
            }
        }
    }

    /** The value at {@code key}: config.yml's, else the default config.yml in the jar, else null. */
    private static @Nullable Object value(ConfigurationSection c, String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalStateException(key + " is read but not in GameConfig.KEYS");
        }
        return c.get(key);
    }

    private static String string(ConfigurationSection c, String key, String def) {
        Object v = value(c, key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + ": needs text, got " + v);
        }
        return s;
    }

    private static long wholeNumber(ConfigurationSection c, String key, long def) {
        Object v = value(c, key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Integer || v instanceof Long)) {
            throw new IllegalArgumentException(key + ": needs a whole number, got " + v);
        }
        return ((Number) v).longValue();
    }

    private static boolean bool(ConfigurationSection c, String key, boolean def) {
        Object v = value(c, key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException(key + ": needs true or false, got " + v);
        }
        return b;
    }

    private static List<?> list(ConfigurationSection c, String key) {
        Object v = value(c, key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new IllegalArgumentException(key + ": needs a list, got " + v);
        }
        return l;
    }

    private static List<String> strings(List<?> values, String key) {
        List<String> out = new ArrayList<>();
        for (Object v : values) {
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException(key + ": needs item or item:amount, got " + v);
            }
            out.add(s);
        }
        return out;
    }

    private static List<?> numbers(Object value, String where, String shape) {
        if (!(value instanceof List<?> l)) {
            throw new IllegalArgumentException(where + " needs " + shape + ", got " + value);
        }
        return l;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String key, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": unknown value " + value, e);
        }
    }

    /** Index of the stage with this name or 1-based number, or -1. */
    int stageIndex(String nameOrNumber) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equalsIgnoreCase(nameOrNumber)) {
                return i;
            }
        }
        try {
            int n = Integer.parseInt(nameOrNumber);
            return n >= 1 && n <= stages.size() ? n - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
