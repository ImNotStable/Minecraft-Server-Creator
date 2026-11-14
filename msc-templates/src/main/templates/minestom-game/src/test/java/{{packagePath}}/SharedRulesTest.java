package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * This game's rules for names and values: stage names, command words, time, kit amounts, spawns and goal boxes. The
 * adapters at the top build a value the way the game does (here every value is set in code (GameConfig.game())); the tables at the bottom list
 * inputs and whether the game takes them. To change a rule, change its check ({@code GameConfig.MAX_TIME},
 * {@code Kit.MAX_AMOUNT}, {@code Stage.isName}, the {@code Box} limits) and its rows here together: the expected
 * messages come from the same constants.
 */class SharedRulesTest {
    private static final Pos AT = new Pos(0.5, -60, 0.5);

    private void stageNamed(String name) {
        new Stage(name, AT);
    }

    private void stagesNamed(List<String> names) {
        TestGames.withStages(names.stream().map(n -> new Stage(n, AT)).toList());
    }

    private void time(long time) {
        TestGames.with(time, List.of(new Stage("a", AT)));
    }

    private void kitAmount(int amount) {
        Kit.item(Material.BREAD, amount);
    }

    private void spawn(double[] v) {
        new Stage("a", new Pos(v[0], v[1], v[2], (float) v[3], (float) v[4]));
    }

    private void goal(int[] c) {
        Box.of(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    // ---- The rule tables. ----

    /** An input and whether the game takes it. */
    record Case<T>(T input, boolean taken) {
        @Override
        public String toString() {
            String shown = input instanceof double[] d ? Arrays.toString(d)
                    : input instanceof int[] i ? Arrays.toString(i) : String.valueOf(input);
            return (taken ? "takes " : "refuses ") + shown.codePoints()
                    .mapToObj(c -> c < ' ' || c > '~' ? String.format("\\u%04x", c) : Character.toString(c))
                    .collect(Collectors.joining());
        }
    }

    static <T> Case<T> ok(T input) {
        return new Case<>(input, true);
    }

    static <T> Case<T> no(T input) {
        return new Case<>(input, false);
    }

    /** Stage names: one word that /game stage can select and chat can type (Stage.isName). */
    static final List<Case<String>> STAGE_NAMES = List.of(
            ok("start"), ok("Boss_Room"), ok("boss-room"), ok("level.2"), ok("2"), ok("été"), ok("竜"),
            no(""), no(" "), no("boss room"), no(" start"), no("start "), no("boss\troom"), no("boss\nroom"),
            no("boss room"), no("boss​room"), no("§cboss"), no("boss\u0007"));

    /** Two stages' names in one game: /game stage ignores case, so names that differ only in case clash. */
    static final List<Case<List<String>>> NAME_PAIRS = List.of(
            ok(List.of("start", "arena")), ok(List.of("arena", "arena2")), no(List.of("arena", "ARENA")),
            no(List.of("Arena", "arena")), no(List.of("I", "ı")));

    /** Command lines as /game splits them: on runs of whitespace. */
    static final Map<String, List<String>> LINES = Map.of(
            "", List.of(),
            "status", List.of("status"),
            "  stage   arena  Steve ", List.of("stage", "arena", "Steve"),
            "stage\tarena", List.of("stage", "arena"));

    /** Lines being typed (tab completion): a trailing space starts the next, empty word. */
    static final Map<String, List<String>> TYPING = Map.of(
            "", List.of(""),
            "st", List.of("st"),
            "stage ", List.of("stage", ""),
            "  stage   ar", List.of("stage", "ar"));

    /** Time of day: 0 to GameConfig.MAX_TIME. */
    static final List<Case<Long>> TIMES = List.of(no(-1L), ok(0L), ok(6000L), ok(23999L), no(24000L));

    /** Kit amounts: 1 to Kit.MAX_AMOUNT, a full inventory. */
    static final List<Case<Integer>> AMOUNTS = List.of(no(-1), no(0), ok(1), ok(64), ok(2304), no(2305));

    /**
     * Spawns, {x, y, z, yaw, pitch}: inside the world and finite. Minestom's Pos wraps yaw and clamps pitch itself, so
     * a view direction is never refused.
     */
    static final List<Case<double[]>> SPAWNS = List.of(
            ok(new double[] {0.5, -60, 0.5, 0, 0}),
            ok(new double[] {30000000, 2031, -30000000, 360, 90}),
            ok(new double[] {-30000000, -2032, 30000000, -360, -90}),
            no(new double[] {30000000.5, 0, 0, 0, 0}),
            no(new double[] {0, 0, -30000001, 0, 0}),
            no(new double[] {0, 2031.5, 0, 0, 0}),
            no(new double[] {0, -2033, 0, 0, 0}),
            no(new double[] {Double.NaN, 0, 0, 0, 0}),
            no(new double[] {0, Double.POSITIVE_INFINITY, 0, 0, 0}));

    /** Goal boxes, {x1, y1, z1, x2, y2, z2}: whole block corners inside the world, in any order. */
    static final List<Case<int[]>> GOALS = List.of(
            ok(new int[] {-2, -61, 10, 2, -58, 12}),
            ok(new int[] {2, -58, 12, -2, -61, 10}),
            ok(new int[] {30000000, 2031, 30000000, -30000000, -2032, -30000000}),
            no(new int[] {30000001, 0, 0, 0, 0, 0}),
            no(new int[] {0, 0, 0, 0, 0, -30000001}),
            no(new int[] {0, 2032, 0, 0, 0, 0}),
            no(new int[] {0, 0, 0, 0, -2033, 0}));

    @TestFactory
    Stream<DynamicTest> stageNames() {
        return cases(STAGE_NAMES, this::stageNamed, Stage.NAME_RULE);
    }

    @TestFactory
    Stream<DynamicTest> stageNamesDifferInMoreThanCase() {
        return cases(NAME_PAIRS, this::stagesNamed, "duplicate stage name");
    }

    @TestFactory
    Stream<DynamicTest> timeOfDay() {
        return cases(TIMES, this::time, "from 0 to " + GameConfig.MAX_TIME);
    }

    @TestFactory
    Stream<DynamicTest> kitAmounts() {
        return cases(AMOUNTS, this::kitAmount, "from 1 to " + Kit.MAX_AMOUNT);
    }

    @TestFactory
    Stream<DynamicTest> spawns() {
        return cases(SPAWNS, this::spawn, "");
    }

    @TestFactory
    Stream<DynamicTest> goals() {
        return cases(GOALS, this::goal, "");
    }

    @Test
    void commandLinesSplitOnRunsOfWhitespace() {
        LINES.forEach((line, words) -> assertThat(GameCommand.words(line, false)).as(line).containsExactlyElementsOf(words));
        TYPING.forEach((line, words) -> assertThat(GameCommand.words(line, true)).as(line).containsExactlyElementsOf(words));
    }

    /** Builds something from an input; throws IllegalArgumentException when the input is refused. */
    interface Build<T> {
        void accept(T input) throws Exception;
    }

    /** Each case as a test: a taken input builds, a refused one fails with a message that contains {@code says}. */
    private static <T> Stream<DynamicTest> cases(List<Case<T>> table, Build<T> build, String says) {
        return table.stream().map(c -> DynamicTest.dynamicTest(c.toString(), () -> {
            if (c.taken()) {
                assertThatCode(() -> build.accept(c.input())).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> build.accept(c.input())).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(says);
            }
        }));
    }
}
