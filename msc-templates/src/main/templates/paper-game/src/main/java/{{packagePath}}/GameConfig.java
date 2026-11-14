package {{packageName}};

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Everything in config.yml, checked once on start. A missing key takes its default; a value of the wrong type, a bad
 * value or an unknown key fails with a message naming the key path and the value it got. Every value is read through
 * the typed readers below ({@link Need}), so a decimal, a too-big number, NaN or a quoted number fails with the same
 * kind of message. To add a setting, add its key to {@link #KEYS} (or {@link #STAGE_KEYS}), a record component (or a
 * {@link Stage} one) read here, its default in config.yml and in GameConfigTest's defaults test, and a bad-value case
 * in GameConfigTest.
 */
public record GameConfig(String world, GameMode gameMode, Difficulty difficulty, int time, boolean naturalSpawns,
                         boolean keepInventory, Kit kit, List<Stage> stages) {

    /** The top-level keys config.yml may hold. Any other key fails, so a typo cannot silently fall back to a default. */
    static final List<String> KEYS = List.of(
            "world", "game-mode", "difficulty", "time", "natural-spawns", "keep-inventory", "kit", "stages");
    /** The keys a stage may hold. */
    static final List<String> STAGE_KEYS = List.of("name", "spawn", "goal");

    /** The last tick of a day: {@code time} is 0 to this. */
    static final int MAX_TIME = 23999;
    /** Time of day: 0 sunrise, 6000 noon, 13000 night, 18000 midnight. */
    static final Need<Integer> TIME = wholeNumber(0, MAX_TIME);
    /** A kit amount: at most a full inventory (36 stacks of 64). */
    static final Need<Integer> AMOUNT = wholeNumber(1, Kit.MAX_AMOUNT);
    /** A block coordinate (a goal corner). */
    static final Need<Integer> BLOCK_XZ = wholeNumber(-Box.MAX_XZ, Box.MAX_XZ);
    static final Need<Integer> BLOCK_Y = wholeNumber(Box.MIN_Y, Box.MAX_Y);
    /** A position (a spawn) and a view direction. */
    static final Need<Double> XZ = number(-Box.MAX_XZ, Box.MAX_XZ);
    static final Need<Double> Y = number(Box.MIN_Y, Box.MAX_Y);
    static final Need<Double> YAW = number(-360, 360);
    static final Need<Double> PITCH = number(-90, 90);

    static GameConfig from(ConfigurationSection c) {
        unknownKeys(c.getKeys(false), KEYS, "");
        return new GameConfig(
                text(c, "world", "world"),
                parse(GameMode.class, "game-mode", text(c, "game-mode", "adventure")),
                parse(Difficulty.class, "difficulty", text(c, "difficulty", "normal")),
                TIME.get(value(c, "time"), "time", 6000),
                bool(c, "natural-spawns", false),
                bool(c, "keep-inventory", true),
                kit(list(c, "kit")),
                stages(list(c, "stages")));
    }

    private static List<Stage> stages(List<?> raw) {
        List<Stage> stages = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String where = "stages[" + i + "]";
            Map<?, ?> s = STAGE.get(raw.get(i), where);
            String name = STAGE_NAME.get(s.get("name"), where + ".name");
            where += " (" + name + ")";
            unknownKeys(s.keySet(), STAGE_KEYS, where + ": ");
            for (Stage earlier : stages) {
                if (Stage.sameName(earlier.name(), name)) {
                    throw new IllegalArgumentException("stages[" + i + "]: duplicate stage name " + name);
                }
            }
            Object goal = s.get("goal");
            stages.add(new Stage(name, spot(s.get("spawn"), where + ".spawn"),
                    goal == null ? null : box(goal, where + ".goal")));
        }
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("stages: add at least one stage");
        }
        return List.copyOf(stages);
    }

    /** {@code [x, y, z]} or {@code [x, y, z, yaw, pitch]}. */
    private static Spot spot(@Nullable Object value, String where) {
        List<?> v = SPOT.get(value, where);
        boolean view = v.size() == 5;
        return new Spot(XZ.get(v.get(0), where + "[0]"), Y.get(v.get(1), where + "[1]"), XZ.get(v.get(2), where + "[2]"),
                view ? (float) (double) YAW.get(v.get(3), where + "[3]") : 0,
                view ? (float) (double) PITCH.get(v.get(4), where + "[4]") : 0);
    }

    /** {@code [x1, y1, z1, x2, y2, z2]}, corners in any order. */
    private static Box box(Object value, String where) {
        List<?> v = BOX.get(value, where);
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) {
            c[i] = (i % 3 == 1 ? BLOCK_Y : BLOCK_XZ).get(v.get(i), where + "[" + i + "]");
        }
        return Box.of(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    /** The amount after the last colon of an entry, unless that part is an item id ({@code minecraft:bread}). */
    private static final Pattern KIT_ENTRY = Pattern.compile("(.+):(?![a-z_][a-z0-9_./-]*$)(.*)", Pattern.CASE_INSENSITIVE);

    /** {@code item} or {@code item:amount}; the amount text is read as YAML reads an unquoted value. */
    private static Kit kit(List<?> raw) {
        List<Kit.Entry> items = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String where = "kit[" + i + "]";
            String line = KIT_LINE.get(raw.get(i), where).strip();
            Matcher m = KIT_ENTRY.matcher(line);
            String id = m.matches() ? m.group(1) : line;
            Material material = Material.matchMaterial(id);
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException(where + ": unknown item " + id);
            }
            int amount = m.matches() ? AMOUNT.get(unquoted(m.group(2)), where + " amount") : 1;
            items.add(new Kit.Entry(material, amount));
        }
        return new Kit(List.copyOf(items));
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
     * Shorthands: each takes the section and key, or a raw value (a stage's s.get("key")) and the key path to show in
     * the error. A missing value (null) takes the default.
     */

    private static String text(ConfigurationSection c, String key, String def) {
        return TEXT.get(value(c, key), key, def);
    }

    private static String text(@Nullable Object v, String key, String def) {
        return TEXT.get(v, key, def);
    }

    private static int wholeNumber(ConfigurationSection c, String key, int def) {
        return WHOLE_NUMBER.get(value(c, key), key, def);
    }

    private static int wholeNumber(@Nullable Object v, String key, int def) {
        return WHOLE_NUMBER.get(v, key, def);
    }

    private static double number(ConfigurationSection c, String key, double def) {
        return NUMBER.get(value(c, key), key, def);
    }

    private static double number(@Nullable Object v, String key, double def) {
        return NUMBER.get(v, key, def);
    }

    private static boolean bool(ConfigurationSection c, String key, boolean def) {
        return TRUE_OR_FALSE.get(value(c, key), key, def);
    }

    private static boolean bool(@Nullable Object v, String key, boolean def) {
        return TRUE_OR_FALSE.get(v, key, def);
    }

    private static List<?> list(ConfigurationSection c, String key) {
        return LIST.get(value(c, key), key, List.of());
    }

    // ---- Typed readers. Every number, true/false, text and list in config.yml is read by these, and only these. ----

    /**
     * What a config value must be ({@code what}, as the error says it) and how to read it ({@code read} gives null
     * when the value is not that). {@link #get(Object, String)} fails with {@code <key path>: needs <what>, got <value>}.
     */
    record Need<T>(String what, Function<Object, @Nullable T> read) {
        /** The value read; fails, naming {@code key}, when it is missing or not what is needed. */
        T get(@Nullable Object v, String key) {
            T t = v == null ? null : read.apply(v);
            if (t == null) {
                throw new IllegalArgumentException(key + ": needs " + what + ", got " + got(v) + quotesHint(v));
            }
            return t;
        }

        /** As {@link #get(Object, String)}, but a missing value takes {@code def}. */
        T get(@Nullable Object v, String key, T def) {
            return v == null ? def : get(v, key);
        }

        /**
         * "; remove the quotes" when the value is text that YAML, unquoted, reads as a valid value meaning the same:
         * {@code '6000'} and {@code 'true'} get it; {@code '09'} (still text), {@code '010'} (octal 8) and
         * {@code 'tRUE'} (still text) do not.
         */
        private String quotesHint(@Nullable Object v) {
            if (v instanceof String s) {
                Object u = unquoted(s);
                if (!(u instanceof String) && u != null && read.apply(u) != null && sameValue(s, u)) {
                    return "; remove the quotes";
                }
            }
            return "";
        }

        private static boolean sameValue(String text, Object unquoted) {
            if (unquoted instanceof Boolean) {
                return true;
            }
            try {
                return new BigDecimal(text.strip()).compareTo(new BigDecimal(unquoted.toString())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    static final Need<String> TEXT = new Need<>("text", v -> v instanceof String s && !s.isBlank() ? s : null);
    static final Need<String> KIT_LINE = new Need<>("item or item:amount", TEXT.read());
    static final Need<Boolean> TRUE_OR_FALSE = new Need<>("true or false", v -> v instanceof Boolean b ? b : null);
    static final Need<Integer> WHOLE_NUMBER = wholeNumber(Integer.MIN_VALUE, Integer.MAX_VALUE);
    /** Any finite number, whole or decimal. */
    static final Need<Double> NUMBER = number(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    /** A stage name ({@link Stage#isName}). */
    static final Need<String> STAGE_NAME = new Need<>("a stage name, " + Stage.NAME_RULE,
            v -> v instanceof String s && Stage.isName(s) ? s : null);
    static final Need<List<?>> LIST = list("a list");
    static final Need<List<?>> SPOT = list("[x, y, z] or [x, y, z, yaw, pitch]", 3, 5);
    static final Need<List<?>> BOX = list("[x1, y1, z1, x2, y2, z2]", 6);
    static final Need<Map<?, ?>> STAGE = new Need<>("{name: ..., spawn: [x, y, z]}",
            v -> v instanceof Map<?, ?> m ? m : null);

    /**
     * A whole number from {@code min} to {@code max}: a decimal, a too-big number or text is refused. {@link #WHOLE_NUMBER}
     * takes any int. Fails at once when {@code min > max}.
     */
    static Need<Integer> wholeNumber(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("wholeNumber(" + min + ", " + max + "): min is above max");
        }
        String what = min == Integer.MIN_VALUE && max == Integer.MAX_VALUE
                ? "a whole number" : "a whole number from " + min + " to " + max;
        return new Need<>(what, v -> (v instanceof Integer || v instanceof Long)
                && ((Number) v).longValue() >= min && ((Number) v).longValue() <= max ? (int) ((Number) v).longValue() : null);
    }

    /**
     * A finite number, whole or decimal, from {@code min} to {@code max}: NaN, infinity and text are refused. An infinite
     * bound means no limit on that side ({@code number(0, Double.POSITIVE_INFINITY)} is "a number from 0 (no upper
     * limit)"); {@link #NUMBER} has none. Fails at once, so the plugin cannot start, when a bound is NaN, when
     * {@code min > max}, or when the range holds no finite number.
     */
    static Need<Double> number(double min, double max) {
        String declared = "number(" + min + ", " + max + "): ";
        if (Double.isNaN(min) || Double.isNaN(max)) {
            throw new IllegalArgumentException(declared + "a bound is NaN; for no limit on a side use"
                    + " Double.NEGATIVE_INFINITY or Double.POSITIVE_INFINITY, or NUMBER for no limit at all");
        }
        if (min > max) {
            throw new IllegalArgumentException(declared + "min is above max");
        }
        if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException(declared + "no finite number is in this range");
        }
        String what = min == Double.NEGATIVE_INFINITY
                ? (max == Double.POSITIVE_INFINITY ? "a number" : "a number up to " + plainNumber(max) + " (no lower limit)")
                : max == Double.POSITIVE_INFINITY ? "a number from " + plainNumber(min) + " (no upper limit)"
                : "a number from " + plainNumber(min) + " to " + plainNumber(max);
        return new Need<>(what, v -> {
            if (!(v instanceof Number n)) {
                return null;
            }
            double d = n.doubleValue();
            return Double.isFinite(d) && d >= min && d <= max ? d : null;
        });
    }

    /** A list, of one of {@code sizes} when given. */
    static Need<List<?>> list(String what, int... sizes) {
        return new Need<>(what, v -> v instanceof List<?> l
                && (sizes.length == 0 || Arrays.stream(sizes).anyMatch(n -> n == l.size())) ? l : null);
    }

    // ---- End of typed readers. ----

    /**
     * The number as the messages show it: {@code 5}, {@code 0.5}, {@code 0.0001}, never in E notation, so YAML reads
     * it back as the same number. NaN and infinity, which have no such form, are {@code NaN}, {@code infinity} and
     * {@code minus infinity}.
     */
    static String plainNumber(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "infinity" : "minus infinity";
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** The text as YAML reads it without quotes ({@code "6000"} is 6000, {@code "09"} stays text), or the text itself. */
    static @Nullable Object unquoted(String text) {
        String s = text.strip();
        if (s.isEmpty() || s.contains("\n") || s.contains("\r")) {
            return text;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("v: " + s);
        } catch (InvalidConfigurationException e) {
            return text;
        }
        Set<String> keys = yaml.getKeys(false);
        if (keys.isEmpty()) {
            return null; // v: ~, v: null or v: # a comment
        }
        return keys.equals(Set.of("v")) ? yaml.get("v") : text;
    }

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
            case Double d when d.isNaN() -> "not a number (.nan)";
            case Double d when d.isInfinite() -> d > 0 ? "infinity (.inf)" : "minus infinity (-.inf)";
            case Number n -> "the decimal " + n;
            case List<?> l -> "the list " + plain(l);
            case Map<?, ?> m -> "the map " + plain(m);
            case ConfigurationSection s -> "the map " + plain(s);
            default -> String.valueOf(v);
        };
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

    /** Index of the stage with this name or else this 1-based number, or -1. A name wins over a number. */
    int stageIndex(String nameOrNumber) {
        for (int i = 0; i < stages.size(); i++) {
            if (Stage.sameName(stages.get(i).name(), nameOrNumber)) {
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
