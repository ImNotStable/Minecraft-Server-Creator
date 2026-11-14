package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Follows the "add a setting" and "add a stage" steps of each game template's CLAUDE.md on a scaffolded project, the
 * way the doc words them, and runs the project's tests: a documented change must not turn an unrelated test red
 * (#401). For paper-game also settings with three kinds of range, a {@code /game} subcommand that parses its own
 * number and a score in {@code Progress} (#431, #432). Only runs via
 * {@code ./gradlew :msc-templates:verifyTemplates}. When a template's text changes, an edit below that no longer
 * matches fails with the file and the text it looked for.
 */
@Tag("template-build")
class TemplateRecipesTest {
    private static final String MAIN = "src/main/java/com/example/verifyme/";
    private static final String TEST = "src/test/java/com/example/verifyme/";
    private static final String START_GOAL = "Box.of(-2, -61, 10, 2, -58, 12)";
    private static final String ARENA_GOAL = "Box.of(-2, -61, 50, 2, -58, 52)";

    /** Replace the one occurrence of {@code old} in {@code file} with {@code replacement}. */
    record Edit(String file, String old, String replacement) {
    }

    @Test
    void paperGameRecipesKeepTheBuildGreen() throws IOException, InterruptedException {
        followAndTest(Templates.PAPER_GAME, List.of(
                // Settings: each key in KEYS, a record component read in from, its default in config.yml and in the
                // defaults test, and a bad-value case. One plain whole number, and three with their own range (a Need
                // and its row in ConfigNumbersTest): 1 to 10, 1 to 3 (which leaves out 5) and a decimal 0 to 1.
                new Edit(MAIN + "GameConfig.java", "\"kit\", \"stages\");",
                        "\"kit\", \"stages\", \"rounds\", \"teams\", \"lives\", \"drop-chance\");"),
                new Edit(MAIN + "GameConfig.java", "Kit kit, List<Stage> stages)",
                        "Kit kit, List<Stage> stages, int rounds, int teams, int lives, double dropChance)"),
                new Edit(MAIN + "GameConfig.java", "    static final Need<Double> PITCH = number(-90, 90);\n",
                        "    static final Need<Double> PITCH = number(-90, 90);\n"
                                + "    static final Need<Integer> TEAMS = wholeNumber(1, 10);\n"
                                + "    static final Need<Integer> LIVES = wholeNumber(1, 3);\n"
                                + "    static final Need<Double> DROP_CHANCE = number(0, 1);\n"
                                + "    static final Need<Integer> WAVES = wholeNumber(1, 50);\n"),
                new Edit(MAIN + "GameConfig.java", "stages(list(c, \"stages\")));",
                        "stages(list(c, \"stages\")),\n                wholeNumber(c, \"rounds\", 3),\n"
                                + "                TEAMS.get(value(c, \"teams\"), \"teams\", 2),\n"
                                + "                LIVES.get(value(c, \"lives\"), \"lives\", 3),\n"
                                + "                DROP_CHANCE.get(value(c, \"drop-chance\"), \"drop-chance\", 0.5));"),
                new Edit("src/main/resources/config.yml", "keep-inventory: true\n",
                        "keep-inventory: true\n# Rounds per game.\nrounds: 3\n# Teams, 1 to 10.\nteams: 2\n"
                                + "# Lives, 1 to 3.\nlives: 3\n# Chance a mob drops loot, 0 to 1.\ndrop-chance: 0.5\n"),
                new Edit(TEST + "GameConfigTest.java", "defaults.stages()));", "defaults.stages(), 3, 2, 3, 0.5));"),
                new Edit(TEST + "GameConfigTest.java", "Bad.top(\"world\", \"5\"",
                        "Bad.top(\"rounds\", \"many\", \"rounds: needs a whole number, got the text 'many'\"),\n"
                                + "            Bad.top(\"teams\", \"11\","
                                + " \"teams: needs a whole number from 1 to 10, got the whole number 11\"),\n"
                                + "            Bad.top(\"lives\", \"'5'\","
                                + " \"lives: needs a whole number from 1 to 3, got the text '5'\"),\n"
                                + "            Bad.top(\"drop-chance\", \"1.5\","
                                + " \"drop-chance: needs a number from 0 to 1, got the decimal 1.5\"),\n"
                                + "            Bad.top(\"world\", \"5\""),
                new Edit(TEST + "ConfigNumbersTest.java", "setting(\"time\", GameConfig.TIME, 0, 23999),\n",
                        "setting(\"time\", GameConfig.TIME, 0, 23999),\n"
                                + "            setting(\"teams\", GameConfig.TEAMS, 1, 10),\n"
                                + "            setting(\"lives\", GameConfig.LIVES, 1, 3),\n"
                                + "            setting(\"drop-chance\", GameConfig.DROP_CHANCE, 0, 1),\n"
                                + "            stageSetting(\"waves\", GameConfig.WAVES, 1, 50),\n"),
                // A stage setting with a range: its key in STAGE_KEYS, a Stage component read in stages with its Need,
                // and a bad-value case.
                new Edit(MAIN + "GameConfig.java", "List.of(\"name\", \"spawn\", \"goal\");",
                        "List.of(\"name\", \"spawn\", \"goal\", \"waves\");"),
                new Edit(MAIN + "Stage.java", "@Nullable Box goal)", "@Nullable Box goal, int waves)"),
                new Edit(MAIN + "GameConfig.java", "where + \".goal\")));",
                        "where + \".goal\"), WAVES.get(s.get(\"waves\"), where + \".waves\", 1)));"),
                new Edit(TEST + "GameConfigTest.java", "Bad.stage(\"spawn\", \"{name: a}\"",
                        "Bad.stage(\"waves\", \"{name: a, spawn: [0, 0, 0], waves: '2'}\","
                                + " \"stages[0] (a).waves: needs a whole number from 1 to 50,"
                                + " got the text '2'; remove the quotes\"),\n"
                                + "            Bad.stage(\"spawn\", \"{name: a}\""),
                // Another stage, copied into config.yml.
                new Edit("src/main/resources/config.yml", "    spawn: [0.5, -60, 40.5, 0, 0]\n",
                        "    spawn: [0.5, -60, 40.5, 0, 0]\n    goal: [-2, -61, 50, 2, -58, 52]\n"
                                + "  - name: boss\n    spawn: [0.5, -60, 80.5, 0, 0]\n"),
                // A /game subcommand for a piece of state, reading its own number argument, with its test.
                new Edit(MAIN + "GameCommand.java", "            default -> error(sender, USAGE);\n", """
                        case "wave" -> {
                            try {
                                int wave = Integer.parseInt(args.length > 1 ? args[1] : "");
                                ok(sender, "skipped to wave " + wave);
                            } catch (NumberFormatException e) {
                                error(sender, "usage: game wave <n>");
                            }
                        }
                        default -> error(sender, USAGE);
                        """.indent(12)),
                new Edit(TEST + "VerifyMePluginTest.java", "    @Test\n    void badArgumentsGetAUsefulReply() {\n", """
                        @Test
                        void waveSkipsAhead() {
                            assertThat(console("game wave 5")).containsExactly("skipped to wave 5");
                            assertThat(console("game wave x")).containsExactly("usage: game wave <n>");
                        }

                        @Test
                        void badArgumentsGetAUsefulReply() {
                        """.indent(4)),
                // A per-player score in Progress, as its Javadoc suggests, read back from progress.yml.
                new Edit(MAIN + "Progress.java", "    private final Path file;\n",
                        "    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();\n"
                                + "    private final Path file;\n"),
                new Edit(MAIN + "Progress.java", "            var section = yaml.getConfigurationSection(\"stage\");\n", """
                        var scoreSection = yaml.getConfigurationSection("score");
                        if (scoreSection != null) {
                            for (String key : scoreSection.getKeys(false)) {
                                scores.put(playerId(key), scoreSection.getInt(key));
                            }
                        }
                        var section = yaml.getConfigurationSection("stage");
                        """.indent(12)),
                new Edit(MAIN + "Progress.java", "    public int size() {\n", """
                        public int score(UUID player) {
                            return scores.getOrDefault(player, 0);
                        }

                        public int size() {
                        """.indent(4)),
                new Edit(TEST + "ProgressTest.java",
                        "    @Test\n    void flushWithoutChangesWritesNothing() throws IOException {\n", """
                        @Test
                        void readsScores() throws IOException {
                            UUID steve = UUID.randomUUID();
                            Path file = dir.resolve("progress.yml");
                            Files.writeString(file, "score:\\n  " + steve + ": 7\\n");

                            assertThat(new Progress(file).load().score(steve)).isEqualTo(7);
                        }

                        @Test
                        void flushWithoutChangesWritesNothing() throws IOException {
                        """.indent(4))));
    }

    @Test
    void fabricGameRecipesKeepTheBuildGreen() throws IOException, InterruptedException {
        followAndTest(Templates.FABRIC_GAME, javaGameEdits("Spot",
                new Edit(MAIN + "GameConfig.java", "List<Stage> stages) {", "List<Stage> stages, int rounds) {"),
                new Edit(MAIN + "GameConfig.java", "new Spot(0.5, -60, 80.5))));",
                        "new Spot(0.5, -60, 80.5))),\n                3);"),
                new Edit(TEST + "TestGames.java", "Kit.of(), stages);", "Kit.of(), stages, 3);")));
    }

    @Test
    void minestomGameRecipesKeepTheBuildGreen() throws IOException, InterruptedException {
        followAndTest(Templates.MINESTOM_GAME, javaGameEdits("Pos",
                new Edit(TEST + "GameThreadsTest.java", START_GOAL + ");", START_GOAL + ", 1);"),
                new Edit(TEST + "GameThreadsTest.java", ARENA_GOAL + ");", ARENA_GOAL + ", 1);"),
                new Edit(MAIN + "GameConfig.java", "Set<String> admins) {", "Set<String> admins, int rounds) {"),
                new Edit(MAIN + "GameConfig.java", "                Set.of());",
                        "                Set.of(), 3);"),
                new Edit(TEST + "TestGames.java", "stages, Set.of(\"Steve\"));",
                        "stages, Set.of(\"Steve\"), 3);")));
    }

    /**
     * The fabric-game and minestom-game steps: another stage in {@code game()}, then a {@code Stage} field with a
     * value in every {@code new Stage(...)} the compiler lists, then the {@code GameConfig} field in {@code config}.
     */
    private static List<Edit> javaGameEdits(String pos, Edit... config) {
        String arena = "new Stage(\"arena\", new " + pos + "(0.5, -60, 40.5)";
        List<Edit> stage = List.of(
                new Edit(MAIN + "GameConfig.java", arena + ")",
                        arena + ", " + ARENA_GOAL + "),\n"
                                + "                        new Stage(\"boss\", new " + pos + "(0.5, -60, 80.5))"),
                new Edit(MAIN + "Stage.java", "Box goal) {", "Box goal, int waves) {"),
                new Edit(MAIN + "Stage.java", "this(name, spawn, null);", "this(name, spawn, null, 1);"),
                new Edit(MAIN + "GameConfig.java", START_GOAL + "),", START_GOAL + ", 1),"),
                new Edit(MAIN + "GameConfig.java", ARENA_GOAL + "),", ARENA_GOAL + ", 1),"),
                new Edit(TEST + "TestGames.java", START_GOAL + "),", START_GOAL + ", 1),"));
        return Stream.concat(stage.stream(), List.of(config).stream()).toList();
    }

    private static void followAndTest(String id, List<Edit> edits) throws IOException, InterruptedException {
        ScaffoldResult r = TemplateBuildTest.scaffoldFresh(id, id + "-recipes");
        for (Edit e : edits) {
            Path file = r.root().resolve(e.file());
            String text = Files.readString(file);
            assertThat(text.split(Pattern.quote(e.old()), -1)).as(e.file() + ": " + e.old()).hasSize(2);
            Files.writeString(file, text.replace(e.old(), e.replacement()));
        }
        TemplateBuildTest.gradle(r.root(), "test");
    }
}
