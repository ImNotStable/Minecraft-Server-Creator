package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
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
 * Every number in config.yml crossed with every bad shape: each must fail naming the key path and what it got. When
 * you add a numeric setting with its own range ({@code Need}) in GameConfig, add its position to {@link #POSITIONS}.
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

    /** A numeric position: its key path, its reader, its range, and the config with the text put there. */
    private record Position(String key, GameConfig.Need<?> need, long min, long max, UnaryOperator<String> yaml) {
        boolean whole() {
            return need.what().startsWith("a whole number");
        }
    }

    private static Position spawn(int i, GameConfig.Need<?> need, long min, long max) {
        return new Position("stages[0] (a).spawn[" + i + "]", need, min, max,
                x -> "stages: [{name: a, spawn: " + with("[0, 0, 0, 0, 0]", i, x) + "}]\n");
    }

    private static Position goal(int i) {
        boolean y = i % 3 == 1;
        return new Position("stages[0] (a).goal[" + i + "]", y ? GameConfig.BLOCK_Y : GameConfig.BLOCK_XZ,
                y ? Box.MIN_Y : -Box.MAX_XZ, y ? Box.MAX_Y : Box.MAX_XZ,
                x -> "stages: [{name: a, spawn: [0, 0, 0], goal: " + with("[0, 0, 0, 1, 1, 1]", i, x) + "}]\n");
    }

    /** The flow list with item {@code i} replaced by {@code x}. */
    private static String with(String list, int i, String x) {
        List<String> items = new ArrayList<>(List.of(list.substring(1, list.length() - 1).split(", ")));
        items.set(i, x);
        return "[" + String.join(", ", items) + "]";
    }

    private static final List<Position> POSITIONS = List.of(
            new Position("time", GameConfig.TIME, 0, 23999, x -> "time: " + x + "\n" + STAGE),
            new Position("kit[0] amount", GameConfig.AMOUNT, 1, 2304, x -> "kit: [\"bread:" + x + "\"]\n" + STAGE),
            spawn(0, GameConfig.XZ, -Box.MAX_XZ, Box.MAX_XZ),
            spawn(1, GameConfig.Y, Box.MIN_Y, Box.MAX_Y),
            spawn(2, GameConfig.XZ, -Box.MAX_XZ, Box.MAX_XZ),
            spawn(3, GameConfig.YAW, -360, 360),
            spawn(4, GameConfig.PITCH, -90, 90),
            goal(0), goal(1), goal(2), goal(3), goal(4), goal(5));

    /** A bad shape: the YAML text put at a position and how the message shows it (null: valid there). */
    private record Shape(String name, String text, String got) {
    }

    private static List<Shape> shapes(Position p) {
        return List.of(
                new Shape("decimal", "1.5", p.whole() ? "the decimal 1.5" : null),
                new Shape("above the range", String.valueOf(p.max() + 1), "the whole number " + (p.max() + 1)),
                new Shape("below the range", String.valueOf(p.min() - 1), "the whole number " + (p.min() - 1)),
                new Shape("too big for an int", "99999999999", "the whole number 99999999999"),
                new Shape("too big for a long", "99999999999999999999",
                        "the whole number 99999999999999999999, which is too big"),
                new Shape("NaN", ".nan", "not a number (.nan)"),
                new Shape("infinity", ".inf", "infinity (.inf)"),
                new Shape("minus infinity", "-.inf", "minus infinity (-.inf)"),
                new Shape("quoted", "'5'", "the text '5'; remove the quotes"),
                new Shape("quoted octal", "'010'", "the text '010'"),
                new Shape("leading zero", "09", "the text '09'"),
                new Shape("quoted out of range", "'99999999999'", "the text '99999999999'"),
                new Shape("list", "[1]", "the list [1]"),
                new Shape("map", "{}", "the map {}"),
                // An empty top-level key takes its default; anywhere else nothing is not a number.
                new Shape("null", "~", p.key().equals("time") ? null : "nothing"));
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

    @Test
    void theEndsOfEachRangeLoad() throws InvalidConfigurationException {
        for (Position p : POSITIONS) {
            assertThat(parse(p.yaml().apply(String.valueOf(p.max())))).as(p.key()).isNotNull();
            assertThat(parse(p.yaml().apply(String.valueOf(p.min())))).as(p.key()).isNotNull();
            assertThat(p.need().what()).as(p.key()).endsWith(" from " + p.min() + " to " + p.max());
        }
    }

    /** Fails when GameConfig gets a numeric reader with a range that no position above uses: add its position. */
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
        assertThat(POSITIONS.stream().map(Position::need).toList()).containsAll(declared);
    }

    /** Ways to read a number, true/false or list from config without the typed readers. */
    private static final Pattern UNTYPED_READ = Pattern.compile(
            "instanceof\\s+(Number|Integer|Long|Double|Float|Short|Byte|BigInteger|BigDecimal|Boolean)\\b"
                    + "|\\.(int|long|double|float|short|byte)Value\\(\\)"
                    + "|\\b(parseInt|parseLong|parseDouble|parseFloat|parseBoolean)\\("
                    + "|\\b(get|is)(Int|Long|Double|Boolean)(List)?\\(|NumberConversions");
    private static final String READERS_START = "// ---- Typed readers.";
    private static final String READERS_END = "// ---- End of typed readers.";

    /**
     * Fails when a number or true/false is read outside GameConfig's typed readers ({@code Need}): read it with one,
     * so a decimal, a too-big number, NaN or a quoted number fails with the same message naming the key path.
     */
    @Test
    void numbersAreReadOnlyByTheTypedReaders() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (file.endsWith("GameConfig.java")) {
                    int start = text.indexOf(READERS_START);
                    int end = text.indexOf(READERS_END);
                    assertThat(start).as("typed readers block in GameConfig").isPositive().isLessThan(end);
                    assertThat(UNTYPED_READ.matcher(text.substring(start, end)).find()).as("guard pattern").isTrue();
                    text = text.substring(0, start) + text.substring(end);
                }
                List<String> lines = text.lines().toList();
                for (String line : lines) {
                    if (UNTYPED_READ.matcher(line).find()) {
                        found.add(file.getFileName() + ": " + line.strip());
                    }
                }
            }
        }
        assertThat(found).as("read these through GameConfig's typed readers").isEmpty();
    }
}
