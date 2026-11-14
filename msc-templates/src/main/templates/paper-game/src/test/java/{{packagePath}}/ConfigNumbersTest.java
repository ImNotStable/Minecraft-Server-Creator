package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Every ranged number in config.yml crossed with every bad shape: each must fail naming the key path and what it got.
 * When you add a setting with its own range ({@code Need}) in GameConfig, add one row to {@link #POSITIONS}:
 * {@code setting("rounds", GameConfig.ROUNDS, 1, 10)} for a top-level key, {@code stageSetting(...)} for a stage key.
 * What each shape must do there follows from the row's range and from whether the reader takes decimals.
 */
class ConfigNumbersTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static final String STAGE = "stages: [{name: a, spawn: [0, 0, 0]}]\n";

    /**
     * A ranged number: its key path, its reader and range, whether a missing value ({@code ~}) takes a default, and
     * the config with the text put there.
     */
    private record Position(String key, GameConfig.Need<?> need, double min, double max, boolean hasDefault,
                            UnaryOperator<String> yaml) {
        boolean whole() {
            return need.what().startsWith("a whole number");
        }

        /** Whether the reader takes this number. */
        boolean takes(double d) {
            return d >= min && d <= max && (!whole() || d == Math.rint(d));
        }
    }

    /** A top-level setting read with a default: {@code key: 5}. */
    private static Position setting(String key, GameConfig.Need<?> need, double min, double max) {
        return new Position(key, need, min, max, true, x -> key + ": " + x + "\n" + STAGE);
    }

    /** A stage setting read with a default: {@code stages: [{name: a, spawn: [0, 0, 0], key: 5}]}. */
    private static Position stageSetting(String key, GameConfig.Need<?> need, double min, double max) {
        return new Position("stages[0] (a)." + key, need, min, max, true,
                x -> "stages: [{name: a, spawn: [0, 0, 0], " + key + ": " + x + "}]\n");
    }

    private static Position spawn(int i, GameConfig.Need<?> need, double min, double max) {
        return new Position("stages[0] (a).spawn[" + i + "]", need, min, max, false,
                x -> "stages: [{name: a, spawn: " + with("[0, 0, 0, 0, 0]", i, x) + "}]\n");
    }

    private static Position goal(int i) {
        boolean y = i % 3 == 1;
        return new Position("stages[0] (a).goal[" + i + "]", y ? GameConfig.BLOCK_Y : GameConfig.BLOCK_XZ,
                y ? Box.MIN_Y : -Box.MAX_XZ, y ? Box.MAX_Y : Box.MAX_XZ, false,
                x -> "stages: [{name: a, spawn: [0, 0, 0], goal: " + with("[0, 0, 0, 1, 1, 1]", i, x) + "}]\n");
    }

    /** The flow list with item {@code i} replaced by {@code x}. */
    private static String with(String list, int i, String x) {
        List<String> items = new ArrayList<>(List.of(list.substring(1, list.length() - 1).split(", ")));
        items.set(i, x);
        return "[" + String.join(", ", items) + "]";
    }

    private static final List<Position> POSITIONS = List.of(
            setting("time", GameConfig.TIME, 0, 23999),
            new Position("kit[0] amount", GameConfig.AMOUNT, 1, 2304, false,
                    x -> "kit: [\"bread:" + x + "\"]\n" + STAGE),
            spawn(0, GameConfig.XZ, -Box.MAX_XZ, Box.MAX_XZ),
            spawn(1, GameConfig.Y, Box.MIN_Y, Box.MAX_Y),
            spawn(2, GameConfig.XZ, -Box.MAX_XZ, Box.MAX_XZ),
            spawn(3, GameConfig.YAW, -360, 360),
            spawn(4, GameConfig.PITCH, -90, 90),
            goal(0), goal(1), goal(2), goal(3), goal(4), goal(5));

    /** A shape: the YAML text put at a position and how the message shows it (null: the value loads). */
    private record Shape(String name, String text, String got) {
    }

    /** A number written plainly: it loads when the position takes it, else the message shows it as YAML read it. */
    private static Shape number(Position p, String name, String text) {
        boolean loads = p.takes(Double.parseDouble(text));
        return new Shape(name, text, loads ? null : GameConfig.got(GameConfig.unquoted(text)));
    }

    private static List<Shape> shapes(Position p) {
        String min = GameConfig.plainNumber(p.min());
        String hintWhenTaken = p.takes(99999999999d) ? "; remove the quotes" : "";
        return List.of(
                // Loads where decimals are taken, and fails as a decimal where only whole numbers are.
                number(p, "decimal", GameConfig.plainNumber(Math.floor((p.min() + p.max()) / 2) + 0.5)),
                number(p, "above the range", GameConfig.plainNumber(p.max() + 1)),
                number(p, "below the range", GameConfig.plainNumber(p.min() - 1)),
                number(p, "too big for an int", "99999999999"),
                number(p, "too big for a long", "99999999999999999999"),
                new Shape("NaN", ".nan", "not a number (.nan)"),
                new Shape("infinity", ".inf", "infinity (.inf)"),
                new Shape("minus infinity", "-.inf", "minus infinity (-.inf)"),
                new Shape("quoted", "'" + min + "'", "the text '" + min + "'; remove the quotes"),
                new Shape("quoted octal", "'010'", "the text '010'"),
                new Shape("leading zero", "09", "the text '09'"),
                new Shape("quoted out of range", "'99999999999'", "the text '99999999999'" + hintWhenTaken),
                new Shape("list", "[1]", "the list [1]"),
                new Shape("map", "{}", "the map {}"),
                new Shape("null", "~", p.hasDefault() ? null : "nothing"));
    }

    private static GameConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return GameConfig.from(c);
    }

    @TestFactory
    Stream<DynamicTest> everyNumberRefusesEveryBadShape() {
        return POSITIONS.stream().flatMap(p -> shapes(p).stream().map(s -> DynamicTest.dynamicTest(
                p.key() + " " + s.name(), () -> {
                    String yaml = p.yaml().apply(s.text());
                    if (s.got() == null) {
                        assertThat(parse(yaml)).as(yaml).isNotNull();
                    } else {
                        assertThatThrownBy(() -> parse(yaml)).as(yaml).isInstanceOf(IllegalArgumentException.class)
                                .hasMessage(p.key() + ": needs " + p.need().what() + ", got " + s.got());
                    }
                })));
    }

    /** Both ends load, and the row's range is the one its reader says (fix the row when this fails). */
    @Test
    void theEndsOfEachRangeLoad() throws InvalidConfigurationException {
        for (Position p : POSITIONS) {
            String min = GameConfig.plainNumber(p.min());
            String max = GameConfig.plainNumber(p.max());
            assertThat(p.need().what()).as(p.key()).endsWith(" from " + min + " to " + max);
            assertThat(parse(p.yaml().apply(max))).as(p.key()).isNotNull();
            assertThat(parse(p.yaml().apply(min))).as(p.key()).isNotNull();
        }
    }

    /** Fails when GameConfig gets a numeric reader with a range that no row above uses: add its row. */
    @Test
    void everyRangedReaderHasAPosition() throws IllegalAccessException {
        Set<GameConfig.Need<?>> declared = new HashSet<>();
        for (Field f : GameConfig.class.getDeclaredFields()) {
            if (f.getType() == GameConfig.Need.class && f.get(null) instanceof GameConfig.Need<?> need
                    && need.what().matches("a (whole )?number from .*")) {
                declared.add(need);
            }
        }
        assertThat(declared).isNotEmpty();
        assertThat(POSITIONS.stream().map(Position::need).toList()).as("add a row to POSITIONS for each")
                .containsAll(declared);
    }
}
