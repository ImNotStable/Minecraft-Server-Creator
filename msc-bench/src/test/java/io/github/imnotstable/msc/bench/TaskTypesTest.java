package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.bench.check.Checks;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #462: every key of a task.json that is not a number (those are in {@link TaskNumbersTest}) is read by its type. A
 * value of another type is refused naming the file, the key and the type it takes, never turned into another value
 * ({@code "startBefore": "yes"} was false) or a ClassCastException text ({@code "server": true}).
 */
class TaskTypesTest {

    /** One task with every non-number key of a task.json and of each check type, each at a valid value. */
    private static final String VALID = """
            {"category":"code","title":"t","platform":"paper","fixtures":[],
             "scaffold":{"template":"minestom-game","name":"N","package":"p"},
             "server":{"startBefore":true,"setup":["say hi"]},
             "resetBeforeChecks":{"delete":["server/plugins/P/config.yml"]},
             "checks":[
              {"type":"file","name":"f","path":"a","contains":"x"},
              {"type":"command","run":["x"],"outputMatches":"y","addFiles":"hidden"},
              {"type":"png","path":"a.png","hue":{"from":0,"to":10}},
              {"type":"zip","path":"a.zip","entries":["e"],"pngEntries":[{"entry":"a.png"}],
               "textEntries":[{"entry":"t","contains":"c"}]},
              {"type":"rcon","command":"list","expect":"players"},
              {"type":"blocks","blocks":[{"pos":[0,0,0],"block":"stone"}],
               "regions":[{"from":[0,0,0],"to":[0,0,0],"block":"stone"}]},
              {"type":"player","jar":"a.jar","expect":["x"],"command":["java"],"port":"1","player":"P",
               "script":["s"],"console":["c"],"ready":"r"}]}
            """;

    private static final String STRING = "a string";
    private static final String BOOL = "true or false";
    private static final String LIST = "a list";
    private static final String OBJECT = "an object";

    /** Each key, as a path in {@link #VALID}, with the type it takes; a list of strings is a list whose [0] is a string. */
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        strings("fixtures");
        KEYS.put("category", STRING);
        KEYS.put("title", STRING);
        KEYS.put("platform", STRING);
        KEYS.put("scaffold", OBJECT);
        for (String key : List.of("template", "name", "package")) {
            KEYS.put("scaffold." + key, STRING);
        }
        KEYS.put("server", OBJECT);
        KEYS.put("server.startBefore", BOOL);
        strings("server.setup");
        KEYS.put("resetBeforeChecks", OBJECT);
        strings("resetBeforeChecks.delete");
        KEYS.put("checks", LIST);
        KEYS.put("checks[0]", OBJECT);
        for (String key : List.of("type", "name", "path", "contains")) {
            KEYS.put("checks[0]." + key, STRING);
        }
        strings("checks[1].run");
        KEYS.put("checks[1].outputMatches", STRING);
        KEYS.put("checks[1].addFiles", STRING);
        KEYS.put("checks[2].hue", OBJECT);
        strings("checks[3].entries");
        KEYS.put("checks[3].pngEntries", LIST);
        KEYS.put("checks[3].pngEntries[0]", OBJECT);
        KEYS.put("checks[3].pngEntries[0].entry", STRING);
        KEYS.put("checks[3].textEntries", LIST);
        KEYS.put("checks[3].textEntries[0].entry", STRING);
        KEYS.put("checks[3].textEntries[0].contains", STRING);
        KEYS.put("checks[4].command", STRING);
        KEYS.put("checks[4].expect", STRING);
        KEYS.put("checks[5].blocks", LIST);
        KEYS.put("checks[5].blocks[0]", OBJECT);
        KEYS.put("checks[5].blocks[0].block", STRING);
        KEYS.put("checks[5].regions", LIST);
        KEYS.put("checks[5].regions[0].block", STRING);
        KEYS.put("checks[6].jar", STRING);
        for (String key : List.of("expect", "command", "script", "console")) {
            strings("checks[6]." + key);
        }
        for (String key : List.of("port", "player", "ready")) {
            KEYS.put("checks[6]." + key, STRING);
        }
    }

    /** A value of each JSON type, as the file writes it and as a refusal shows it. */
    private static final Map<String, String> VALUES = Map.of(STRING, "\"yes\"", BOOL, "true", LIST, "[]",
            OBJECT, "{}", "a number", "5");

    @TempDir
    Path dir;

    @Test
    void theValidTaskLoads() throws IOException {
        TaskDef task = load(JsonParser.parseString(VALID));
        assertThat(task.checks()).hasSize(7);
        assertThat(task.server().setup()).containsExactly("say hi");
    }

    static Stream<Arguments> wrongTypes() {
        List<Arguments> cases = new ArrayList<>();
        KEYS.forEach((key, type) -> VALUES.forEach((valueType, value) -> {
            if (!valueType.equals(type)) {
                cases.add(Arguments.of(key, type, value));
            }
        }));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} = {2}")
    @MethodSource("wrongTypes")
    void aValueOfAnotherTypeIsRefusedNamingTheKeyAndTheTypeItTakes(String key, String type, String value) {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        set(json, key, JsonParser.parseString(value));
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessage("Task t: task.json " + key + ": " + value + " is not " + type);
    }

    /** Each regex key of {@link #VALID} (#508), as a path; a list of regexes by its first entry. */
    private static final List<String> REGEX_KEYS = List.of("checks[0].contains", "checks[1].outputMatches",
            "checks[3].textEntries[0].contains", "checks[4].expect", "checks[6].expect[0]", "checks[6].ready");

    /** Each path key of {@link #VALID} (#508); a list of paths by its first entry. */
    private static final List<String> PATH_KEYS = List.of("fixtures[0]", "checks[0].path", "checks[1].addFiles",
            "checks[2].path", "checks[3].path", "checks[6].jar");

    @Test
    void theRegexKeysAreStringKeys() {
        REGEX_KEYS.forEach(key -> assertThat(KEYS.get(key)).as(key).isEqualTo(STRING));
    }

    static Stream<Arguments> unreadableStrings() {
        List<Arguments> rows = new ArrayList<>();
        for (String key : REGEX_KEYS) {
            rows.add(Arguments.of(key, "(abc", "\"(abc\" is not a regex: Unclosed group at index 4"));
            rows.add(Arguments.of(key, "x\n[" + "a".repeat(100), "\"x\\n[" + "a".repeat(57)
                    + "\"... is not a regex: Unclosed character class at index 102"));
            // #672: the library's description repeats the regex, and is escaped as the quoted value is.
            rows.add(Arguments.of(key, "\\p{a\u2028b\u202ec}", "\"\\\\p{a\\u2028b\\u202ec}\" is not a regex: "
                    + "Unknown character property name {a\\u2028b\\u202ec} at index 8"));
        }
        for (String key : PATH_KEYS) {
            rows.add(Arguments.of(key, "a\0b", "\"a\\u0000b\" is not a path: Nul character not allowed"));
        }
        rows.add(Arguments.of("checks[6].port", "70000",
                "\"70000\" is not a port from 1 to 65535 or a placeholder such as {serverPort}"));
        rows.add(Arguments.of("checks[6].port", "x", "\"x\" is not a port from 1 to 65535 or a placeholder such as "
                + "{serverPort}"));
        return rows.stream();
    }

    /** #508: a string a key cannot use (a bad regex, no path, no port) is refused in one line naming its key path. */
    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("unreadableStrings")
    void aStringTheKeyCannotUseIsRefusedInOneLineNamingTheKey(String key, String value, String says) {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        if (key.startsWith("fixtures")) {
            json.add("fixtures", JsonParser.parseString("[\"x\"]"));
        }
        set(json, key, new JsonPrimitive(value));
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessage("Task t: task.json " + key + ": " + says);
    }

    @Test
    void startBeforeIsReadAsWritten() throws IOException {
        // The strict read must not have turned every true into false, or every false into true.
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        assertThat(load(json).server().startBefore()).isTrue();
        set(json, "server.startBefore", JsonParser.parseString("false"));
        set(json, "server.setup", JsonParser.parseString("[]"));
        assertThat(load(json).server().startBefore()).isFalse();
    }

    @Test
    void theResetPathsAreKeptNormalized() throws IOException {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        set(json, "resetBeforeChecks.delete", JsonParser.parseString("[\"server/./a/../config.yml\", \"b\\\\c\"]"));
        assertThat(load(json).reset().delete()).containsExactly("server/config.yml", "b/c");
    }

    /**
     * #495: a reset deletes files after the agent, so every path must name something inside the workspace, and a
     * misspelt key, which would leave the reset out silently, is refused.
     */
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "resetBeforeChecks.delete[0] | `\"../x\"`        | resetBeforeChecks.delete[0]: \"../x\" is not inside the workspace",
            "resetBeforeChecks.delete[0] | `\"a/../../x\"`   | resetBeforeChecks.delete[0]: \"a/../../x\" is not inside the workspace",
            "resetBeforeChecks.delete[0] | `\"..\\\\x\"` | resetBeforeChecks.delete[0]: \"..\\\\x\" is not inside the workspace",
            "resetBeforeChecks.delete[0] | `\".\"`           | resetBeforeChecks.delete[0]: \".\" is not inside the workspace",
            "resetBeforeChecks.delete[0] | `\"a/..\"`        | resetBeforeChecks.delete[0]: \"a/..\" is not inside the workspace",
            "resetBeforeChecks.delete[0] | `\"/etc/x\"`      | resetBeforeChecks.delete[0]: \"/etc/x\" is absolute",
            "resetBeforeChecks.delete[0] | `\"C:/x\"`        | resetBeforeChecks.delete[0]: \"C:/x\" is absolute",
            "resetBeforeChecks.delete[0] | `\" \"`           | resetBeforeChecks.delete[0]: \" \" is blank",
            "resetBeforeChecks.restore   | `[\"a\"]`         | resetBeforeChecks.restore: unknown key",
            "resetBeforeCheck            | `{}`                | resetBeforeCheck: unknown key"})
    void aResetThatCouldMissOrLeaveTheWorkspaceIsRefused(String key, String value, String message) {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        set(json, key, JsonParser.parseString(value));
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Task t: task.json " + message);
    }

    @Test
    void validHasACheckOfEveryType() {
        // So that the walk below reaches every object a check type has, and a new type cannot be left out.
        List<String> types = new ArrayList<>();
        JsonParser.parseString(VALID).getAsJsonObject().getAsJsonArray("checks")
                .forEach(c -> types.add(c.getAsJsonObject().get("type").getAsString()));
        assertThat(types).containsExactlyInAnyOrderElementsOf(Checks.KEYS.types().keySet());
    }

    /** #502: the path of every object in {@link #VALID}, such as {@code checks[3].pngEntries[0]} ({@code ""} first). */
    static Stream<String> objects() {
        List<String> out = new ArrayList<>();
        walk(JsonParser.parseString(VALID), "", out);
        return out.stream();
    }

    private static void walk(JsonElement value, String path, List<String> out) {
        if (value.isJsonObject()) {
            out.add(path);
            value.getAsJsonObject().entrySet().forEach(e -> walk(e.getValue(),
                    path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out));
        } else if (value.isJsonArray()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) {
                walk(value.getAsJsonArray().get(i), path + "[" + i + "]", out);
            }
        }
    }

    /**
     * #502: an unknown key in any object of a task.json, at any depth, is refused naming its path and the keys there
     * are, which hold every key the object has in {@link #VALID}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("objects")
    void anUnknownKeyInAnyObjectIsRefused(String object) throws IOException {
        String path = object.isEmpty() ? "zzz" : object + ".zzz";
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        JsonElement holder = json;
        for (JsonElement step : steps(json, object)) {
            holder = step;
        }
        List<String> present = new ArrayList<>(holder.getAsJsonObject().keySet());
        set(json, path, JsonParser.parseString("1"));
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Task t: task.json " + path + ": unknown key; the keys are [")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("\n"))
                .satisfies(e -> assertThat(keysIn(e.getMessage())).containsAll(present));
    }

    /** #502: every key of every check type, misspelt, is refused with that type's keys; the rows come from the reader. */
    static Stream<Arguments> misspeltCheckKeys() {
        List<Arguments> cases = new ArrayList<>();
        Checks.KEYS.types().forEach((type, keys) -> keys.names().stream().filter(k -> !k.equals("type"))
                .forEach(key -> cases.add(Arguments.of(type, key + "x", keys.names()))));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("misspeltCheckKeys")
    void aMisspeltKeyOfEachCheckTypeIsRefused(String type, String key, List<String> keys) {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        JsonObject check = new JsonObject();
        check.addProperty("type", type);
        check.addProperty(key, "x");
        JsonArray checks = new JsonArray();
        checks.add(check);
        json.add("checks", checks);
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessage("Task t: task.json checks[0]." + key + ": unknown key; the keys are " + keys);
    }

    /** The issue's example: {@code "contain"} for {@code "contains"} made the file check pass for any answer.txt. */
    @Test
    void aFileCheckWithContainIsRefusedNotPassedForAnyFile() {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        JsonObject file = json.getAsJsonArray("checks").get(0).getAsJsonObject();
        file.add("contain", file.remove("contains"));
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessage("Task t: task.json checks[0].contain: unknown key; the keys are [type, name, path, contains]");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "`{\"name\":\"n\",\"path\":\"a\"}` | checks[0].type: is missing; the types are [file, command, png, zip, rcon, blocks, player]",
            "`{\"type\":\"files\",\"path\":\"a\"}` | checks[0].type: \"files\" is not one of [file, command, png, zip, rcon, blocks, player]"})
    void aCheckWithoutAKnownTypeIsRefusedNamingTheTypes(String check, String message) {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        JsonArray checks = new JsonArray();
        checks.add(JsonParser.parseString(check));
        json.add("checks", checks);
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessage("Task t: task.json " + message);
    }

    /** The objects along {@code path}, such as {@code checks[3].pngEntries[0]}, from the root's child on. */
    private static List<JsonElement> steps(JsonObject root, String path) {
        List<JsonElement> out = new ArrayList<>();
        JsonElement holder = root;
        Matcher step = Pattern.compile("(\\w+)|\\[(\\d+)]").matcher(path);
        while (step.find()) {
            holder = step.group(1) != null ? holder.getAsJsonObject().get(step.group(1))
                    : holder.getAsJsonArray().get(Integer.parseInt(step.group(2)));
            out.add(holder);
        }
        return out;
    }

    /** The keys a refusal lists after {@code the keys are}. */
    private static List<String> keysIn(String message) {
        String list = message.substring(message.indexOf("the keys are [") + "the keys are [".length(),
                message.lastIndexOf(']'));
        return List.of(list.split(", "));
    }

    private TaskDef load(JsonElement json) throws IOException {
        Path task = Files.createDirectories(dir.resolve("t"));
        Files.createDirectories(task.resolve("hidden"));
        Files.writeString(task.resolve("task.json"), json.toString());
        Files.writeString(task.resolve("prompt.md"), "Do it.");
        return TaskLoader.load(task);
    }

    /** {@code key} takes a list of strings: the list itself, and its first entry a string. */
    private static void strings(String key) {
        KEYS.put(key, LIST);
        KEYS.put(key + "[0]", STRING);
    }

    /** Sets the value at a path such as {@code checks[1].run} or {@code checks[1].run[0]}, growing an empty list. */
    private static void set(JsonObject root, String path, JsonElement value) {
        List<Object> steps = new ArrayList<>();
        Matcher step = Pattern.compile("(\\w+)|\\[(\\d+)]").matcher(path);
        while (step.find()) {
            steps.add(step.group(1) != null ? step.group(1) : (Object) Integer.parseInt(step.group(2)));
        }
        JsonElement holder = root;
        for (int i = 0; i < steps.size() - 1; i++) {
            holder = steps.get(i) instanceof Integer index
                    ? holder.getAsJsonArray().get(index)
                    : holder.getAsJsonObject().get((String) steps.get(i));
        }
        Object last = steps.getLast();
        if (last instanceof Integer index) {
            JsonArray array = holder.getAsJsonArray();
            if (array.isEmpty()) {
                array.add(value);
            } else {
                array.set(index, value);
            }
        } else {
            holder.getAsJsonObject().add((String) last, value);
        }
    }
}
