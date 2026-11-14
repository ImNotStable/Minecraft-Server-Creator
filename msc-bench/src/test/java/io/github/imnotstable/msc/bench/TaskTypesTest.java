package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
             "server":{"startBefore":false,"setup":["say hi"]},
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

    @Test
    void startBeforeTrueStartsTheServer() throws IOException {
        // The one value besides false: the strict read must not have turned every true into false either.
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        set(json, "server.startBefore", JsonParser.parseString("true"));
        assertThat(load(json).server().startBefore()).isTrue();
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
            "resetBeforeChecks.delete[0] | `\"..\\\\x\"` | resetBeforeChecks.delete[0]: \"..\\x\" is not inside the workspace",
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
