package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Jeremiah's rule (ADR 0106): the agent creates everything from scratch; msc gives it general primitives, guidance and
 * review checks, never prefabs, presets, canned templates or stock assets. This test keeps new ones from slipping in.
 * Runs in msc-core's repoChecks task over every module's production sources and tools.md.
 * <p>Each list below is a reviewed allowlist. A new entry is a design decision: add it only for a primitive (a shape,
 * fill, brush, API call or check that the agent drives with its own blocks, colours, text and code), and say why in
 * the ADR that adds it. A step, option, constant or style that puts the tool author's design into a server does not go
 * in: write guidance and a review check instead.
 */
class FromScratchTest {
    private static final String SOURCES = "productionSources";
    private static final String TOOL_DOC = "msc-docs/docs/tools.md";
    private static final String BUILD_SCRIPT =
            "msc-core/src/main/java/io/github/imnotstable/msc/core/building/BuildScript.java";

    /** build_batch steps: geometry, brushes and the agent's own blocks, text, images and structures (ADR 0103). */
    private static final Set<String> STEPS = Set.of("fill", "set", "cylinder", "ring", "disc", "sphere", "dome", "line",
            "helix", "bands", "terrain", "path", "scatter", "trim", "decorate", "weather", "flora", "smooth", "erode",
            "blend", "paint", "river", "tunnel", "cave", "sign", "banner", "head", "book", "container", "entity", "frame",
            "stand", "biome", "light", "image", "structure", "copy");

    /** Tool options named like a preset picker, and the ones allowed, each with its reason. */
    private static final Pattern PRESET_OPTION = Pattern.compile(
            "style|styles|preset|presets|theme|themes|prefab|template|kind|room|shape|design|sample|example");
    private static final Map<String, String> ALLOWED_OPTIONS = Map.of(
            "project_scaffold.template", "a bare build and an empty entry point per platform (ADR 0073), no content",
            "model_create.kind", "Minecraft's own model parents (cube_all, item...) the agent's textures go into",
            "api_search.kind", "a Java type kind (class, interface...) to narrow an API search");

    /** Public constants in msc-stdlib: plumbing only, never a look, sound or text for the agent to reuse. */
    private static final Set<String> STDLIB_CONSTANTS = Set.of("Vec3.ZERO", "Json.GSON", "Json.PRETTY",
            "Permissions.DEFAULT", "Arena.SHARED", "Feedback.TIMES");
    /** Public enums in msc-stdlib: names of database kinds, column types and lifecycle states, not content. */
    private static final Set<String> STDLIB_ENUMS = Set.of("DatabaseType", "ColumnType", "Cause", "Phase", "Reason");

    private static final Pattern CASE = Pattern.compile("(?m)^ {12}case (\"[a-z_]+\"(?:, \"[a-z_]+\")*) ->");
    private static final Pattern CONSTANT = Pattern.compile(
            "public static final [\\w.<>, ?\\[\\]]+ ([A-Z][A-Z0-9_]*) =");
    private static final Pattern ENUM = Pattern.compile("public (?:static )?enum (\\w+)");
    /** A colour or decoration chosen in code: MiniMessage tags, Adventure colours, vanilla formatting. */
    private static final Pattern STYLE = Pattern.compile("\"[^\"\\n]*<(?:red|green|blue|gold|yellow|aqua|gray|grey|white"
            + "|black|dark_\\w+|light_purple|bold|b|italic|i|underlined|u|gradient|rainbow)[:>][^\"\\n]*\""
            + "|NamedTextColor\\.[A-Z_]+|ChatFormatting\\.[A-Z_]+|ChatColor\\.[A-Z_]+"
            // Turning off the game's default italics on item text shows the agent's text as written: plumbing.
            + "|TextDecoration\\.(?!State\\b)(?!ITALIC, TextDecoration\\.State\\.FALSE)[A-Z_]+\\b");

    @Test
    void buildBatchHasOnlyPrimitiveSteps() throws IOException {
        String code = SourceText.mask(Files.readString(RepoFiles.path(SOURCES, BUILD_SCRIPT)), false);
        Set<String> steps = new TreeSet<>();
        Matcher m = CASE.matcher(code);
        while (m.find()) {
            for (String quoted : m.group(1).split(", ")) {
                steps.add(quoted.substring(1, quoted.length() - 1));
            }
        }
        assertThat(steps).as("build_batch's steps").contains("fill", "copy");
        assertThat(steps).as("a build_batch step that is not a reviewed primitive. The agent builds everything from "
                + "scratch (ADR 0106): a step that places a design (a tree, roof, room, boat...) does not go in; write "
                + "guidance in guides/build.md and a review check instead. A new primitive goes into STEPS here, with "
                + "its reason in an ADR").isSubsetOf(STEPS);
        String doc = Files.readString(RepoFiles.path("toolDoc", TOOL_DOC));
        String row = doc.substring(doc.indexOf("| `build_batch` |"));
        row = row.substring(0, row.indexOf('\n'));
        assertThat(Pattern.compile("\\b(?:style|kind|room|preset|theme)=").matcher(row).results().map(r -> r.group())
                .toList()).as("build_batch documents a step option that picks a ready-made design (ADR 0106)").isEmpty();
    }

    @Test
    void noToolTakesAPresetPicker() throws IOException {
        List<String> found = new ArrayList<>();
        for (String row : Files.readString(RepoFiles.path("toolDoc", TOOL_DOC)).split("\n")) {
            String[] cells = row.split(" \\| ");
            if (!row.startsWith("| `") || cells.length < 3) {
                continue;
            }
            String tool = cells[0].substring(3, cells[0].indexOf('`', 3));
            for (MatchResult option : Pattern.compile("`(\\w+)`\\??\\s\\(").matcher(cells[1]).results().toList()) {
                String name = option.group(1);
                if (PRESET_OPTION.matcher(name).matches() && !ALLOWED_OPTIONS.containsKey(tool + "." + name)) {
                    found.add(tool + "." + name);
                }
            }
        }
        assertThat(found).as("a tool option that picks a ready-made design (ADR 0106). The agent draws, builds and "
                + "writes everything itself; give it a primitive it drives with its own content, or add the option to "
                + "ALLOWED_OPTIONS here with the reason it picks no design").isEmpty();
    }

    @Test
    void theStandardLibraryShipsNoLooksSoundsOrText() throws IOException {
        Map<String, String> found = new TreeMap<>();
        for (String f : RepoFiles.list(SOURCES)) {
            if (!f.startsWith("msc-stdlib")) {
                continue;
            }
            String text = Files.readString(RepoFiles.path(SOURCES, f));
            String code = SourceText.mask(text, false);
            String type = f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length());
            for (MatchResult c : CONSTANT.matcher(code).results().toList()) {
                if (!STDLIB_CONSTANTS.contains(type + "." + c.group(1))) {
                    found.put(f + ":" + SourceText.line(text, c.start()), "constant " + c.group(1));
                }
            }
            for (MatchResult e : ENUM.matcher(code).results().toList()) {
                if (!STDLIB_ENUMS.contains(e.group(1))) {
                    found.put(f + ":" + SourceText.line(text, e.start()), "enum " + e.group(1));
                }
            }
            for (MatchResult s : STYLE.matcher(code).results().toList()) {
                found.put(f + ":" + SourceText.line(text, s.start()), "style " + s.group());
            }
        }
        assertThat(found).as("msc-stdlib ships a preset (theme, sound, colour, styled or canned text) instead of taking "
                + "the agent's own (ADR 0106). Take it as a parameter or callback; plumbing constants go into "
                + "STDLIB_CONSTANTS or STDLIB_ENUMS here with a reason").isEmpty();
    }

    @Test
    void theChecksFindWhatTheyLookFor() {
        String stdlib = """
                public record Theme(ItemStack border) {
                    public static final Theme DARK = new Theme(null);
                }
                public enum Sounds { CLICK }
                class G { void m() { send(Text.mm("<red>No.")); send(Component.text("x", NamedTextColor.AQUA)); c.setColor(ChatColor.RED); }
                    Component n(Component c) { return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE); }
                    Component u(Component c) { return c.decorate(TextDecoration.UNDERLINED); } }
                // "<red>in a comment" is fine
                """;
        String code = SourceText.mask(stdlib, false);
        assertThat(CONSTANT.matcher(code).results().map(r -> r.group(1)).toList()).containsExactly("DARK");
        assertThat(ENUM.matcher(code).results().map(r -> r.group(1)).toList()).containsExactly("Sounds");
        assertThat(STYLE.matcher(code).results().map(r -> r.group()).toList())
                .containsExactly("\"<red>No.\"", "NamedTextColor.AQUA", "ChatColor.RED", "TextDecoration.UNDERLINED");
        assertThat(CASE.matcher("            case \"fill\" -> {\n                case \"x\" -> 0;\n"
                + "            case \"sign\", \"banner\" -> {").results().map(r -> r.group(1)).toList())
                .containsExactly("\"fill\"", "\"sign\", \"banner\"");
        assertThat(PRESET_OPTION.matcher("style").matches()).isTrue();
        assertThat(PRESET_OPTION.matcher("styles2").matches()).isFalse();
    }
}
