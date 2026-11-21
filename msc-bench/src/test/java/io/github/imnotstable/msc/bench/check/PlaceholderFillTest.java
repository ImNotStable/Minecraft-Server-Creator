package io.github.imnotstable.msc.bench.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.ArgsEchoServer;
import io.github.imnotstable.msc.bench.CatFile;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #578: every string key of every check type is filled with the attempt's placeholders, through one path
 * ({@link Checks#parse}). Each row puts {@code {scaffoldVersion}} into one key, against a workspace laid out with the
 * filled value {@code 1.0.0}, and the check passes only when that key was filled.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PlaceholderFillTest {

    private static final String V = "{scaffoldVersion}";
    private static final String JAVA_CP = "\"{java}\",\"-cp\",\"{classpath}\",";
    private static final String CAT = JAVA_CP + "\"" + CatFile.class.getName() + "\",";
    private static final String ECHO = "\"command\":[" + JAVA_CP + "\"" + ArgsEchoServer.class.getName() + "\"";

    /** The keys that hold numbers (or objects of numbers), which have no placeholders to fill. */
    private static final Map<String, Set<String>> NUMBER_KEYS = Map.of(
            "command", Set.of("timeoutSeconds", "expectExit"),
            "png", Set.of("width", "height", "maxColors", "minOpaqueFraction", "maxOpaqueFraction", "hue"),
            "player", Set.of("timeoutSeconds"));

    @TempDir
    static Path workspace;

    @TempDir
    static Path taskDir;

    private static FakeRconServer rcon;

    /** One key of one check type holding a placeholder: how the check reads (with {@code V} for the placeholder). */
    record Row(String type, String key, String json) {
        String filledJson() {
            return json.replace("{V}", V);
        }

        @Override
        public String toString() {
            return type + "." + key;
        }
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("file", "name", "{\"type\":\"file\",\"name\":\"n-{V}\",\"path\":\"notes-1.0.0.txt\"}"),
                new Row("file", "path", "{\"type\":\"file\",\"path\":\"notes-{V}.txt\"}"),
                new Row("file", "contains", "{\"type\":\"file\",\"path\":\"notes-1.0.0.txt\",\"contains\":\"^v{V}$\"}"),
                new Row("command", "name", "{\"type\":\"command\",\"name\":\"n-{V}\",\"run\":[" + CAT
                        + "\"notes-1.0.0.txt\"]}"),
                new Row("command", "run", "{\"type\":\"command\",\"run\":[" + CAT + "\"notes-{V}.txt\"]}"),
                new Row("command", "outputMatches", "{\"type\":\"command\",\"run\":[" + CAT
                        + "\"notes-1.0.0.txt\"],\"outputMatches\":\"^v{V}$\"}"),
                new Row("command", "addFiles", "{\"type\":\"command\",\"addFiles\":\"hidden-{V}\",\"run\":[" + CAT
                        + "\"added-1.0.0.txt\"]}"),
                new Row("png", "name", "{\"type\":\"png\",\"name\":\"n-{V}\",\"path\":\"icon-1.0.0.png\"}"),
                new Row("png", "path", "{\"type\":\"png\",\"path\":\"icon-{V}.png\",\"width\":16}"),
                new Row("zip", "name", "{\"type\":\"zip\",\"name\":\"n-{V}\",\"path\":\"pack-1.0.0.zip\"}"),
                new Row("zip", "path", "{\"type\":\"zip\",\"path\":\"pack-{V}.zip\"}"),
                new Row("zip", "entries", "{\"type\":\"zip\",\"path\":\"pack-1.0.0.zip\",\"entries\":[\"a-{V}.txt\"]}"),
                new Row("zip", "textEntries.entry", "{\"type\":\"zip\",\"path\":\"pack-1.0.0.zip\","
                        + "\"textEntries\":[{\"entry\":\"a-{V}.txt\",\"contains\":\"v\"}]}"),
                new Row("zip", "textEntries.contains", "{\"type\":\"zip\",\"path\":\"pack-1.0.0.zip\","
                        + "\"textEntries\":[{\"entry\":\"a-1.0.0.txt\",\"contains\":\"^v{V}$\"}]}"),
                new Row("zip", "pngEntries.entry", "{\"type\":\"zip\",\"path\":\"pack-1.0.0.zip\","
                        + "\"pngEntries\":[{\"entry\":\"p-{V}.png\",\"width\":16}]}"),
                new Row("rcon", "name", "{\"type\":\"rcon\",\"name\":\"n-{V}\",\"command\":\"say 1.0.0\",\"expect\":\"v\"}"),
                new Row("rcon", "command", "{\"type\":\"rcon\",\"command\":\"say {V}\",\"expect\":\"^v1\\\\.0\\\\.0$\"}"),
                new Row("rcon", "expect", "{\"type\":\"rcon\",\"command\":\"say 1.0.0\",\"expect\":\"^v{V}$\"}"),
                new Row("blocks", "name", "{\"type\":\"blocks\",\"name\":\"n-{V}\","
                        + "\"blocks\":[{\"pos\":[0,0,0],\"block\":\"minecraft:stone_1.0.0\"}]}"),
                new Row("blocks", "blocks.block", "{\"type\":\"blocks\","
                        + "\"blocks\":[{\"pos\":[0,0,0],\"block\":\"minecraft:stone_{V}\"}]}"),
                new Row("blocks", "regions.block", "{\"type\":\"blocks\","
                        + "\"regions\":[{\"from\":[0,0,0],\"to\":[0,0,1],\"block\":\"minecraft:stone_{V}\"}]}"),
                new Row("player", "name", "{\"type\":\"player\",\"name\":\"n-{V}\",\"jar\":\"srv-1.0.0.jar\","
                        + ECHO + "],\"expect\":[\"Done\"]}"),
                new Row("player", "jar", "{\"type\":\"player\",\"jar\":\"srv-{V}.jar\"," + ECHO + "],\"expect\":[\"Done\"]}"),
                new Row("player", "command", "{\"type\":\"player\",\"jar\":\"srv-1.0.0.jar\"," + ECHO
                        + ",\"arg-{V}\"],\"expect\":[\"args: arg-1\\\\.0\\\\.0\"]}"),
                new Row("player", "ready", "{\"type\":\"player\",\"jar\":\"srv-1.0.0.jar\"," + ECHO
                        + ",\"arg-1.0.0\"],\"ready\":\"args: arg-{V}\",\"expect\":[\"Done\"],\"timeoutSeconds\":30}"),
                new Row("player", "console", "{\"type\":\"player\",\"jar\":\"srv-1.0.0.jar\"," + ECHO
                        + "],\"console\":[\"say {V}\"],\"expect\":[\"got: say 1\\\\.0\\\\.0\"]}"),
                new Row("player", "expect", "{\"type\":\"player\",\"jar\":\"srv-1.0.0.jar\"," + ECHO
                        + "],\"console\":[\"say 1.0.0\"],\"expect\":[\"got: say {V}\"]}"),
                // Used only once a test player joins, which needs a Minecraft server: read, not run.
                new Row("player", "port", "{\"type\":\"player\",\"jar\":\"a.jar\",\"port\":\"{serverPort}\",\"expect\":[\"x\"]}"),
                new Row("player", "player", "{\"type\":\"player\",\"jar\":\"a.jar\",\"player\":\"P{V}\",\"expect\":[\"x\"]}"),
                // The zip holds no pack.mcmeta for it to judge: read, not run.
                new Row("zip", "packFormat", "{\"type\":\"zip\",\"path\":\"pack-1.0.0.zip\",\"packFormat\":\"{V}\"}"),
                new Row("player", "script", "{\"type\":\"player\",\"jar\":\"a.jar\",\"script\":[\"say {V}\"],"
                        + "\"expect\":[\"x\"]}"));
    }

    private static final Set<String> READ_ONLY = Set.of("player.port", "player.player", "player.script",
            "zip.packFormat");

    @BeforeAll
    static void layOutTheFilledWorkspace() throws IOException {
        Files.writeString(workspace.resolve("notes-1.0.0.txt"), "v1.0.0");
        Files.writeString(workspace.resolve("srv-1.0.0.jar"), "stands in for a server jar");
        byte[] png = png();
        Files.write(workspace.resolve("icon-1.0.0.png"), png);
        try (OutputStream out = Files.newOutputStream(workspace.resolve("pack-1.0.0.zip"));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("a-1.0.0.txt"));
            zip.write("v1.0.0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("p-1.0.0.png"));
            zip.write(png);
        }
        // The task loads the check unfilled, so its folder as written exists too; the run copies the filled one.
        Files.createDirectories(taskDir.resolve("hidden-" + V));
        Files.writeString(Files.createDirectories(taskDir.resolve("hidden-1.0.0")).resolve("added-1.0.0.txt"), "v");
        rcon = new FakeRconServer("pw", command -> {
            if (command.equals("say 1.0.0")) {
                return "v1.0.0";
            }
            return command.startsWith("execute if block ") && command.endsWith(" minecraft:stone_1.0.0")
                    ? "Test passed" : "no: " + command;
        });
    }

    @AfterAll
    static void stopRcon() throws IOException {
        rcon.close();
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, 0xFFCC1122);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A filled value with what a regex pass must not read again: placeholders' syntax and a quote's end. */
    private static final String BRACES = "/home/u/{proj}/{plus}/runs/a.b\\E.x";

    private static final Map<String, String> VALUES = Map.of(
            "scaffoldVersion", "1.0.0",
            "java", ProcessHandle.current().info().command().orElse("java"),
            "classpath", System.getProperty("java.class.path"),
            "plus", "a+b",
            "braces", BRACES,
            "serverPort", "25566");

    private static UserJson json(String text) {
        return UserJson.of(JsonParser.parseString(text).getAsJsonObject(), "check.", Checks.KEYS);
    }

    private static CheckResult run(Check check) {
        return check.run(new CheckContext() {
            @Override
            public Path workspace() {
                return workspace;
            }

            @Override
            public Map<String, String> placeholders() {
                return VALUES;
            }

            @Override
            public RconClient rcon() throws IOException {
                return RconClient.connect("127.0.0.1", rcon.port(), "pw", Duration.ofSeconds(5));
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyStringKeyOfEveryCheckIsFilled(Row row) {
        Check check = Checks.parse(json(row.filledJson()), taskDir);
        if (READ_ONLY.contains(row.toString())) {
            boolean port = row.key().equals("port");
            assertThat(Checks.read(json(row.filledJson()).filled(VALUES), taskDir).toString())
                    .contains(port ? "port=25566" : "1.0.0").doesNotContain(port ? "{serverPort}" : V);
            return;
        }
        CheckResult result = run(check);
        assertThat(result.passed()).as(result.toString()).isTrue();
        if (row.key().equals("name")) {
            assertThat(result.name()).isEqualTo("n-1.0.0");
        }
    }

    @Test
    void theTableCoversEveryKeyOfEveryCheckType() {
        Set<String> covered = new HashSet<>();
        rows().forEach(r -> covered.add(r.type() + "." + r.key().split("\\.")[0]));
        Set<String> expected = new HashSet<>();
        Checks.KEYS.types().forEach((type, keys) -> keys.names().stream()
                .filter(k -> !k.equals("type") && !NUMBER_KEYS.getOrDefault(type, Set.of()).contains(k))
                .forEach(k -> expected.add(type + "." + k)));
        assertThat(covered).isEqualTo(expected);
    }

    @Test
    void aRegexMatchesAFilledValueLiterally() {
        // "a+b" as a regex would need one or more "a" then "b"; filled, it is the text a+b.
        assertThat(run(Checks.parse(json("{\"type\":\"command\",\"run\":[" + CAT + "\"plus.txt\"],"
                + "\"outputMatches\":\"^{plus}$\"}"), taskDir))).satisfies(r -> assertThat(r.passed())
                .as(r.toString()).isTrue());
    }

    /** A regex as a task writes it, and a text it must find. */
    record RegexRow(String regex, String text) {
        @Override
        public String toString() {
            return regex + " finds " + text;
        }
    }

    static Stream<RegexRow> regexes() {
        return Stream.of(
                new RegexRow("^\\p{Lu}\\p{L}+$", "Émile"),
                new RegexRow("^\\P{Lu}+$", "émile"),
                new RegexRow("^[\\p{IsLatin}]+$", "abc"),
                new RegexRow("^\\x{FF}$", "ÿ"),
                new RegexRow("^\\N{LATIN SMALL LETTER A}$", "a"),
                // #595: \b{g} is a grapheme boundary, \c takes the next character (\c{ is ";").
                new RegexRow("^a\\b{g}$", "a"),
                new RegexRow("^\\b{g}{plus}\\b{g}$", "a+b"),
                new RegexRow("^\\c{x}$", ";x}"),
                new RegexRow("^\\c[{plus}]$", "\u001Ba+b]"),
                new RegexRow("^[\\c{]{plus}$", ";a+b"),
                new RegexRow("^\\x{41}{plus}$", "Aa+b"),
                new RegexRow("^\\\\{plus}$", "\\a+b"),
                new RegexRow("^\\d{2}{plus}$", "12a+b"),
                new RegexRow("^a{2}b{1,2}$", "aab"),
                new RegexRow("^\\{plus}$", "{plus}"),
                new RegexRow("^\\Q{plus}*\\E$", "a+b*"),
                new RegexRow("^\\Q{unknown}\\E$", "{unknown}"),
                new RegexRow("^{unknown}$", "{unknown}"),
                new RegexRow("^(?:{plus}){2}$", "a+ba+b"),
                new RegexRow("^{braces}$", BRACES),
                new RegexRow("^<{braces}>$", "<" + BRACES + ">"));
    }

    /**
     * #588: a regex key is filled and quoted in one pass over the regex as written. Escape sequences with braces stay
     * regex syntax, a filled value matches literally whatever it holds, and an unknown name matches as written.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("regexes")
    void aRegexKeepsItsEscapesAndMatchesFilledValuesLiterally(RegexRow row) throws IOException {
        Path file = Files.writeString(workspace.resolve("regex-" + Integer.toHexString(row.regex().hashCode())
                + ".txt"), row.text());
        JsonObject check = new JsonObject();
        check.addProperty("type", "file");
        check.addProperty("path", file.getFileName().toString());
        check.addProperty("contains", row.regex());
        // Loaded as written (the task's load), then run filled.
        CheckResult result = run(Checks.parse(UserJson.of(check, "check.", Checks.KEYS), taskDir));
        assertThat(result.passed()).as(result.toString()).isTrue();
    }

    /**
     * #595: a brace right after a letter escape belongs to the escape (its argument, or a repetition), whichever letter
     * it is, so no placeholder is read there; only {@code \Q} opens a section where names are filled.
     */
    @Test
    void aBraceAfterALetterEscapeIsNeverAPlaceholder() {
        for (char letter = 'A'; letter <= 'z'; letter++) {
            if (Character.isLetter(letter) && letter != 'Q') {
                String regex = "\\" + letter + "{plus}";
                assertThat(Placeholders.fillRegex(regex, VALUES)).as(regex).isEqualTo(regex);
            }
        }
        // Not a placeholder and no repetition: refused when the task loads, not read as something else.
        assertThatThrownBy(() -> Checks.parse(json("{\"type\":\"file\",\"path\":\"plus.txt\","
                + "\"contains\":\"\\\\s{plus}\"}"), taskDir)).hasMessageContaining("is not a regex");
    }

    @Test
    void aFilledValueIsNeverFilledAgain() {
        assertThat(Placeholders.fill("{braces} {plus} {unknown}", VALUES)).isEqualTo(BRACES + " a+b {unknown}");
        assertThat(Placeholders.fillRegex("^{braces}$", VALUES)).isEqualTo("^" + Pattern.quote(BRACES) + "$");
        assertThat(Pattern.compile(Placeholders.fillRegex("^{plus}$", VALUES)).matcher("aab").find()).isFalse();
    }

    @BeforeAll
    static void writeThePlusFile() throws IOException {
        Files.writeString(workspace.resolve("plus.txt"), "a+b");
    }

    @Test
    void aValueThatMakesACheckUnreadableFailsItNamingTheKey() {
        CheckResult result = run(Checks.parse(json("{\"type\":\"player\",\"jar\":\"a.jar\",\"port\":\"{plus}\","
                + "\"expect\":[\"x\"]}"), taskDir));
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("check.port").contains("a+b");
    }

    @Test
    void theLoadReadsTheCheckAsWritten() {
        assertThat(Checks.parse(json("{\"type\":\"file\",\"path\":\"notes-" + V + ".txt\"}"), taskDir).name())
                .isEqualTo("file notes-" + V + ".txt");
    }
}
