package {{packageName}};

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * Everything in config.yml, checked once on start. A missing key takes its default; a value of the wrong type, a bad
 * value or an unknown key fails with a message naming the key and the type the value has. To add a setting, add its
 * key to {@link #KEYS} (or {@link #STAGE_KEYS}), a record component (or a {@link Stage} one) read here, its default in
 * config.yml and in GameConfigTest's defaults test, and a bad-value case in GameConfigTest.
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
                text(c, "world", "world"),
                parse(GameMode.class, "game-mode", text(c, "game-mode", "adventure")),
                parse(Difficulty.class, "difficulty", text(c, "difficulty", "normal")),
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
                throw new IllegalArgumentException(
                        where + " needs {name: ..., spawn: [x, y, z]}, got " + got(raw.get(i)));
            }
            Object rawName = s.get("name");
            if (!(rawName instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(
                        where + " needs a name" + (rawName == null ? "" : ", got " + got(rawName)));
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

    /*
     * Readers: each takes the section and key, or a raw value (a stage's s.get("key")) and the name to show in the
     * error. A missing value (null) takes the default; a value of another type fails, naming the type it has.
     */

    private static String text(ConfigurationSection c, String key, String def) {
        return text(value(c, key), key, def);
    }

    private static String text(@Nullable Object v, String key, String def) {
        if (v == null) {
            return def;
        }
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + ": needs text, got " + got(v));
        }
        return s;
    }

    private static long wholeNumber(ConfigurationSection c, String key, long def) {
        return wholeNumber(value(c, key), key, def);
    }

    private static long wholeNumber(@Nullable Object v, String key, long def) {
        if (v == null) {
            return def;
        }
        if (!(v instanceof Integer || v instanceof Long)) {
            throw new IllegalArgumentException(key + ": needs a whole number, got " + got(v, WHOLE_NUMBER));
        }
        return ((Number) v).longValue();
    }

    private static boolean bool(ConfigurationSection c, String key, boolean def) {
        return bool(value(c, key), key, def);
    }

    private static boolean bool(@Nullable Object v, String key, boolean def) {
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException(key + ": needs true or false, got " + got(v, TRUE_OR_FALSE));
        }
        return b;
    }

    private static List<?> list(ConfigurationSection c, String key) {
        Object v = value(c, key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new IllegalArgumentException(key + ": needs a list, got " + got(v));
        }
        return l;
    }

    private static List<String> strings(List<?> values, String key) {
        List<String> out = new ArrayList<>();
        for (Object v : values) {
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException(key + ": needs item or item:amount, got " + got(v));
            }
            out.add(s);
        }
        return out;
    }

    private static List<?> numbers(Object value, String where, String shape) {
        if (!(value instanceof List<?> l)) {
            throw new IllegalArgumentException(where + " needs " + shape + ", got " + got(value));
        }
        return l;
    }

    private static final Pattern WHOLE_NUMBER = Pattern.compile("[-+]?\\d+");
    static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
    private static final Pattern TRUE_OR_FALSE = Pattern.compile("(?i)true|false");

    /**
     * A config value as an error shows it: its YAML type and value, so a quoted {@code '6000'} does not read as the
     * number it looks like ({@code the text '6000'}, {@code the whole number 6000}, {@code the list [6000]}).
     */
    static String got(@Nullable Object v) {
        return switch (v) {
            case null -> "nothing";
            case String s when s.isBlank() -> "empty text";
            case String s -> "the text '" + s + "'";
            case Boolean b -> "the true/false value " + b;
            case Integer i -> "the whole number " + i;
            case Long l -> "the whole number " + l;
            case BigInteger b -> "the whole number " + b + ", which is too big";
            case Number n -> "the decimal " + n;
            case List<?> l -> "the list " + plain(l);
            case Map<?, ?> m -> "the map " + plain(m);
            case ConfigurationSection s -> "the map " + plain(s);
            default -> String.valueOf(v);
        };
    }

    /** As {@link #got(Object)}, and says to remove the quotes when the text would fit without them. */
    static String got(@Nullable Object v, Pattern fitsUnquoted) {
        String got = got(v);
        return v instanceof String s && fitsUnquoted.matcher(s.strip()).matches() ? got + "; remove the quotes" : got;
    }

    /** The value with every nested config section turned into a plain map, so it prints as YAML-like text. */
    private static Object plain(@Nullable Object v) {
        return switch (v) {
            case null -> "null";
            case ConfigurationSection s -> plain(s.getValues(false));
            case Map<?, ?> m -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                m.forEach((k, value) -> out.put(k, plain(value)));
                yield out;
            }
            case List<?> l -> l.stream().map(GameConfig::plain).toList();
            default -> v;
        };
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
