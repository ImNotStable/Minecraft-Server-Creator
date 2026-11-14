package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Config parsing. MockBukkit only provides the item registry for the kit. */
class GameConfigTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static GameConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return GameConfig.from(c);
    }

    @Test
    void readsEverySetting() throws InvalidConfigurationException {
        GameConfig c = parse("""
                game-mode: survival
                difficulty: hard
                time: 1000
                kit: [stone_sword, 'minecraft:arrow:16']
                stages:
                  - {name: start, spawn: [0, -60, 0]}
                  - {name: boss, spawn: [0, -60, 50, 180, 0], goal: [0, 0, 0, 1, 1, 1]}
                """);
        assertThat(c.gameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(c.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(c.time()).isEqualTo(1000);
        assertThat(c.kit().items()).containsExactly(
                new Kit.Entry(Material.STONE_SWORD, 1), new Kit.Entry(Material.ARROW, 16));
        assertThat(c.stageIndex("BOSS")).isEqualTo(1);
        assertThat(c.stageIndex("1")).isZero();
        assertThat(c.stageIndex("3")).isEqualTo(-1);
        assertThat(c.stages().get(0).goal()).isNull();
    }

    /** {@code /game stage} and saved progress: a name wins, then a number as Integer.parseInt reads it. */
    @Test
    void findsAStageByNameFirstThenByNumber() throws InvalidConfigurationException {
        GameConfig c = parse("stages: [{name: start, spawn: [0, 0, 0]}, {name: arena, spawn: [0, 0, 0]},"
                + " {name: '2', spawn: [0, 0, 0]}]\n");
        assertThat(c.stageIndex("ARENA")).isEqualTo(1);
        assertThat(c.stageIndex("2")).as("the stage named 2, not the second stage").isEqualTo(2);
        assertThat(c.stageIndex("3")).isEqualTo(2);
        assertThat(c.stageIndex("01")).isZero();
        assertThat(c.stageIndex("+1")).isZero();
        assertThat(c.stageIndex("0")).isEqualTo(-1);
        assertThat(c.stageIndex("4")).isEqualTo(-1);
        assertThat(c.stageIndex("lava")).isEqualTo(-1);
    }

    @Test
    void theShippedConfigLoadsAndMissingKeysTakeDefaults() throws IOException, InvalidConfigurationException {
        GameConfig shipped = parse(Files.readString(Path.of("src/main/resources/config.yml")));
        assertThat(shipped.stages()).isNotEmpty();
        GameConfig defaults = parse(STAGE);
        assertThat(defaults).isEqualTo(new GameConfig("world", GameMode.ADVENTURE, Difficulty.NORMAL, 6000, false,
                true, new Kit(List.of()), defaults.stages()));
        assertThat(defaults.stages().get(0).goal()).isNull();
    }

    private static final String STAGE = "stages: [{name: a, spawn: [0, 0, 0]}]\n";

    /** A bad value for a key: the config text and the whole message it must fail with. */
    private record Bad(String key, String yaml, String message) {
        /** A bad top-level value next to a valid stage. */
        static Bad top(String key, String value, String message) {
            return new Bad(key, key + ": " + value + "\n" + STAGE, message);
        }

        /** A stage that is bad in {@code key}; the stage is written in full. */
        static Bad stage(String key, String stage, String message) {
            return new Bad("stages[]." + key, "stages: [" + stage + "]\n", message);
        }
    }

    private static final String NAME = "one word of visible characters (no spaces, no §)";
    private static final String TIME = "time: needs a whole number from 0 to " + GameConfig.MAX_TIME;
    private static final String AMOUNT = "kit[0] amount: needs a whole number from 1 to " + Kit.MAX_AMOUNT;

    private static final List<Bad> BAD = List.of(
            Bad.top("world", "5", "world: needs text, got the whole number 5"),
            Bad.top("world", "''", "world: needs text, got empty text"),
            Bad.top("game-mode", "hover", "game-mode: unknown value hover"),
            Bad.top("game-mode", "[adventure]", "game-mode: needs text, got the list [adventure]"),
            Bad.top("difficulty", "brutal", "difficulty: unknown value brutal"),
            Bad.top("time", "night", TIME + ", got the text 'night'"),
            Bad.top("time", "6000.5", TIME + ", got the decimal 6000.5"),
            Bad.top("time", "'6000'", TIME + ", got the text '6000'; remove the quotes"),
            Bad.top("time", "'010'", TIME + ", got the text '010'"),
            Bad.top("time", "09", TIME + ", got the text '09'"),
            Bad.top("time", "99999999999999999999", TIME + ","
                    + " got the whole number 99999999999999999999, which is too big"),
            Bad.top("natural-spawns", "nope", "natural-spawns: needs true or false, got the text 'nope'"),
            Bad.top("natural-spawns", "'false'",
                    "natural-spawns: needs true or false, got the text 'false'; remove the quotes"),
            Bad.top("natural-spawns", "tRUE", "natural-spawns: needs true or false, got the text 'tRUE'"),
            Bad.top("keep-inventory", "no way", "keep-inventory: needs true or false, got the text 'no way'"),
            Bad.top("keep-inventory", "1", "keep-inventory: needs true or false, got the whole number 1"),
            Bad.top("keep-inventory", "[true]", "keep-inventory: needs true or false, got the list [true]"),
            Bad.top("kit", "bread", "kit: needs a list, got the text 'bread'"),
            Bad.top("kit", "{bread: 8}", "kit: needs a list, got the map {bread=8}"),
            Bad.top("kit", "[laser]", "kit[0]: unknown item laser"),
            Bad.top("kit", "[bread, {bread: 8}]", "kit[1]: needs item or item:amount, got the map {bread=8}"),
            Bad.top("kit", "[5]", "kit[0]: needs item or item:amount, got the whole number 5"),
            Bad.top("kit", "['bread:0']", AMOUNT + ", got the whole number 0"),
            Bad.top("kit", "['bread:8.5']", AMOUNT + ", got the decimal 8.5"),
            new Bad("stages", "stages: []", "stages: add at least one stage"),
            new Bad("stages", "stages: start", "stages: needs a list, got the text 'start'"),
            new Bad("stages", "stages: [start]", "stages[0]: needs {name: ..., spawn: [x, y, z]}, got the text 'start'"),
            Bad.stage("name", "{spawn: [0, 0, 0]}", "stages[0].name: needs a stage name, " + NAME + ", got nothing"),
            Bad.stage("name", "{name: 5, spawn: [0, 0, 0]}",
                    "stages[0].name: needs a stage name, " + NAME + ", got the whole number 5"),
            Bad.stage("name", "{name: '', spawn: [0, 0, 0]}",
                    "stages[0].name: needs a stage name, " + NAME + ", got empty text"),
            // /game stage splits on spaces, so it could never select this stage by name.
            Bad.stage("name", "{name: boss room, spawn: [0, 0, 0]}",
                    "stages[0].name: needs a stage name, " + NAME + ", got the text 'boss room'"),
            Bad.stage("name", "{name: a, spawn: [0, 0, 0]}, {name: A, spawn: [0, 0, 0]}",
                    "stages[1]: duplicate stage name A"),
            Bad.stage("spawn", "{name: a}",
                    "stages[0] (a).spawn: needs [x, y, z] or [x, y, z, yaw, pitch], got nothing"),
            Bad.stage("spawn", "{name: a, spawn: 0 0 0}",
                    "stages[0] (a).spawn: needs [x, y, z] or [x, y, z, yaw, pitch], got the text '0 0 0'"),
            Bad.stage("spawn", "{name: a, spawn: [0, 0]}",
                    "stages[0] (a).spawn: needs [x, y, z] or [x, y, z, yaw, pitch], got the list [0, 0]"),
            Bad.stage("spawn", "{name: a, spawn: [0, '-60.5', 0]}", "stages[0] (a).spawn[1]: needs a number from"
                    + " -2032 to 2031, got the text '-60.5'; remove the quotes"),
            Bad.stage("spawn", "{name: a, spawn: [0, .nan, 0]}",
                    "stages[0] (a).spawn[1]: needs a number from -2032 to 2031, got not a number (.nan)"),
            Bad.stage("goal", "{name: a, spawn: [0, 0, 0], goal: 1 2 3 4 5 6}",
                    "stages[0] (a).goal: needs [x1, y1, z1, x2, y2, z2], got the text '1 2 3 4 5 6'"),
            Bad.stage("goal", "{name: a, spawn: [0, 0, 0], goal: {from: [0, 0, 0], to: [1, 1, 1]}}",
                    "stages[0] (a).goal: needs [x1, y1, z1, x2, y2, z2], got the map {from=[0, 0, 0], to=[1, 1, 1]}"),
            Bad.stage("goal", "{name: a, spawn: [0, 0, 0], goal: [0, 0, x, 1, 1, 1]}",
                    "stages[0] (a).goal[2]: needs a whole number from -30000000 to 30000000, got the text 'x'"),
            Bad.stage("goal", "{name: a, spawn: [0, 0, 0], goal: [-12.7, -61, 10, 2, -58, 12]}", "stages[0] (a).goal[0]:"
                    + " needs a whole number from -30000000 to 30000000, got the decimal -12.7"),
            Bad.stage("goal", "{name: a, spawn: [0, 0, 0], goal: [0, 0, 0, 1, 1, 99999999999]}", "stages[0] (a).goal[5]:"
                    + " needs a whole number from -30000000 to 30000000, got the whole number 99999999999"));

    @TestFactory
    Stream<DynamicTest> badValuesNameTheKey() {
        return BAD.stream().map(bad -> DynamicTest.dynamicTest(bad.key() + ": " + bad.yaml().lines().findFirst().orElseThrow(),
                () -> assertThatThrownBy(() -> parse(bad.yaml()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(bad.message())));
    }

    /** Fails when GameConfig learns a key with no bad-value case above: add one to BAD. */
    @Test
    void everyKeyHasABadValueCase() {
        List<String> keys = new ArrayList<>(GameConfig.KEYS);
        GameConfig.STAGE_KEYS.forEach(k -> keys.add("stages[]." + k));
        assertThat(BAD.stream().map(Bad::key).distinct().toList()).containsExactlyInAnyOrderElementsOf(keys);
    }

    /** The message names the typo'd key and lists the known ones, read from the lists so a new key needs no change here. */
    @Test
    void unknownKeysAreNamed() {
        assertThatThrownBy(() -> parse("keep-inventry: false\n" + STAGE)).hasMessage(
                "unknown key keep-inventry (known: " + String.join(", ", GameConfig.KEYS) + ")");
        assertThatThrownBy(() -> parse("stages: [{name: a, spawn: [0, 0, 0], goals: [0, 0, 0, 1, 1, 1]}]"))
                .hasMessage("stages[0] (a): unknown key goals (known: " + String.join(", ", GameConfig.STAGE_KEYS) + ")");
        assertThat(GameConfig.KEYS).contains("keep-inventory", "stages");
        assertThat(GameConfig.STAGE_KEYS).contains("goal");
    }
}
