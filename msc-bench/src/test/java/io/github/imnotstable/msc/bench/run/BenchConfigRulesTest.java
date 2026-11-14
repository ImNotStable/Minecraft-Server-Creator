package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** #406: every value of bench.json that can never work is refused at load, naming its key. */
class BenchConfigRulesTest {

    /** Fields with no impossible value of their own type: the EULA choice is never taken from the file (MachineEula). */
    private static final Set<String> NO_IMPOSSIBLE_VALUE = Set.of("server.acceptEula");

    private static final String VALID = """
            {"agent":{"command":["claude","-p","{prompt}"],"timeoutSeconds":60},
             "modes":{"m":{"extraArgs":["--mcp-config","{mcpConfig}"],"env":{"A":"1"},"mcpConfigTemplate":"mcp.json",
                           "serverEnv":{"B":"{corePort}"},"serverPlugins":["{agentJar}"],"paperCacheSeed":".msc/cache",
                           "requireMcp":"msc"}},
             "server":{"command":["java","-jar","paper.jar"],"paperJar":"paper.jar","paperCacheDirs":["cache"],
                       "downloadsUrl":"https://fill.papermc.io","acceptEula":false,"serverPort":25566,
                       "rconPort":25576,"rconPassword":"pw","startTimeoutSeconds":180},
             "gradleWrapperFrom":"wrapper","paths":{"agentJar":"agent.jar"},"corePort":25590,"minecraftVersion":"26.3"}
            """;

    /** One or more impossible values per field, as JSON; {@code *} in a key is the mode {@code m}. */
    private static final Map<String, List<JsonElement>> CASES = new LinkedHashMap<>();

    static {
        cases("agent.command", "[]", "[\"\"]", "[null]", "[\"claude\",\"-p\"]");
        cases("agent.timeoutSeconds", number("0", "-1"));
        cases("modes", "{}", "{\"m\":null}", "{\"a/b\":{}}", "{\"a,b\":{}}");
        cases("modes.*.extraArgs", "[null]");
        cases("modes.*.env", "{\"A=B\":\"1\"}", "{\"A\":null}", "{\"A\":\"x\\u0000y\"}");
        cases("modes.*.mcpConfigTemplate", "\"\"", "\"missing.json\"");
        cases("modes.*.serverEnv", "{\"\":\"1\"}", "{\"B\":null}");
        cases("modes.*.serverPlugins", "[\"\"]", "[null]", "[\"{nothing}\"]");
        cases("modes.*.paperCacheSeed", "\"\"", "\"../out\"", "\"/abs\"");
        cases("modes.*.requireMcp", "\" \"");
        cases("server.command", "[]", "[\" \"]");
        cases("server.paperJar", "\"\"", "\"missing.jar\"");
        cases("server.paperCacheDirs", "[]", "[null]", "[\"\"]");
        cases("server.downloadsUrl", "\"\"", "\"ftp://host/x\"", "\"not a url\"", "\"https:///nohost\"");
        cases("server.serverPort", number("0", "-5", "65536", "25576"));
        cases("server.rconPort", number("0", "70000", "25566"));
        cases("server.rconPassword", "\"\"", "\"   \"", json("x".repeat(1447)), json("é".repeat(724)),
                json("a\0b"), json("a\ud800b"), json("\udc00"));
        cases("server.startTimeoutSeconds", number("0", "-1"));
        cases("gradleWrapperFrom", "\"\"", "\"no-wrapper\"");
        cases("paths", "{\"agentJar\":\"\"}", "{\"agentJar\":null}", "{\"prompt\":\"x\",\"agentJar\":\"a\"}",
                "{\"a b\":\"x\",\"agentJar\":\"a\"}");
        cases("corePort", number("0", "65536", "25576", "25566"));
        cases("minecraftVersion", "\"\"", "\"26.3/../x\"");
    }

    @TempDir
    Path dir;

    @Test
    void everyFieldBenchConfigReadsHasAnImpossibleValueCase() {
        Set<String> fields = fields(BenchConfig.class, "");
        Set<String> needed = new TreeSet<>(fields);
        needed.removeAll(NO_IMPOSSIBLE_VALUE);
        assertThat(CASES.keySet()).as("a new field needs its impossible values here").containsAll(needed);
        assertThat(fields).as("cases for fields that no longer exist").containsAll(CASES.keySet());
    }

    @Test
    void everyNumberKeyIsReadThroughTheStrictPass() {
        // #416: an int key bound by Gson alone is read with getAsInt, which cuts decimals and wraps big numbers.
        Set<String> ints = new TreeSet<>();
        for (String field : fields(BenchConfig.class, "")) {
            if (componentType(field) == int.class) {
                ints.add(field);
            }
        }
        assertThat(BenchConfigRules.NUMBERS.keySet()).containsExactlyInAnyOrderElementsOf(ints);
        assertThat(CASES.keySet()).containsAll(ints);
    }

    static Stream<Arguments> numbersAsWritten() {
        return Stream.of(
                Arguments.of("server.rconPort", "4294992872", "server.rconPort: 4294992872 is not a TCP port"),
                Arguments.of("server.serverPort", "99999999999", "server.serverPort: 99999999999 is not a TCP port"),
                Arguments.of("agent.timeoutSeconds", "1800.9",
                        "agent.timeoutSeconds: 1800.9 is not a whole number of seconds above 0"),
                Arguments.of("server.startTimeoutSeconds", "0.5",
                        "server.startTimeoutSeconds: 0.5 is not a whole number of seconds above 0"),
                Arguments.of("corePort", "\"25590\"", "corePort: \"25590\" is not a TCP port"),
                Arguments.of("server.rconPort", "null", "server.rconPort: null is not a TCP port"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("numbersAsWritten")
    void aRefusedNumberIsShownAsTheFileWritesIt(String key, String value, String message) throws IOException {
        Path config = write(with(key, JsonParser.parseString(value)));
        assertThatThrownBy(() -> BenchConfig.load(config, home())).hasMessageContaining(message);
    }

    @Test
    void theBoundNumbersAreTheCheckedOnes() throws IOException {
        // Whole numbers written as 1.0 or 1e3 are the same numbers, and bind as such; nothing is cut or wrapped.
        JsonObject json = with("server.rconPort", JsonParser.parseString("25577.0"));
        json.getAsJsonObject("agent").add("timeoutSeconds", JsonParser.parseString("1.2e3"));
        json.getAsJsonObject("server").add("startTimeoutSeconds", JsonParser.parseString("2147483647"));
        BenchConfig config = BenchConfig.load(write(json), home());
        assertThat(config.server().rconPort()).isEqualTo(25577);
        assertThat(config.agent().timeoutSeconds()).isEqualTo(1200);
        assertThat(config.server().startTimeoutSeconds()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aRefusedNumberStillLetsTheOtherRulesReport() throws IOException {
        JsonObject json = with("server.rconPort", JsonParser.parseString("4294992872"));
        json.getAsJsonObject("server").add("paperJar", new JsonPrimitive(""));
        Path config = write(json);
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .hasMessageContaining("server.rconPort: 4294992872")
                .hasMessageContaining("server.paperJar: is blank");
    }

    @Test
    void theValidBaseLoads() throws IOException {
        BenchConfig config = BenchConfig.load(write(JsonParser.parseString(VALID)), home());
        assertThat(config.server().rconPassword()).isEqualTo("pw");
    }

    static Stream<Arguments> impossibleValues() {
        return CASES.entrySet().stream().flatMap(e -> e.getValue().stream().map(v -> Arguments.of(e.getKey(), v)));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("impossibleValues")
    void anImpossibleValueIsRefusedAtLoadNamingItsKey(String key, JsonElement value) throws IOException {
        Path config = write(with(key, value));
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Bad bench config")
                .hasMessageContaining(key.replace("*", "m"));
    }

    /**
     * #462: each key of bench.json with a value of every other JSON type, and each list and map with an entry of the
     * wrong type; then the type the key takes, as the refusal says it. Number keys have their own cases above.
     */
    static Stream<Arguments> wrongTypes() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("agent", "an object");
        keys.put("server", "an object");
        keys.put("modes.*", "an object");
        for (String field : fields(BenchConfig.class, "")) {
            Class<?> type = componentType(field);
            if (type == String.class) {
                keys.put(field, "a string");
            } else if (type == boolean.class) {
                keys.put(field, "true or false");
            } else if (type == List.class) {
                keys.put(field, "a list");
                keys.put(field + "[0]", "a string");
            } else if (type == Map.class) {
                keys.put(field, "an object");
                if (!field.equals("modes")) {
                    keys.put(field + ".K", "a string");
                }
            }
        }
        Map<String, String> values = Map.of("a string", "\"yes\"", "true or false", "true", "a list", "[]",
                "an object", "{}", "a number", "5");
        List<Arguments> cases = new ArrayList<>();
        keys.forEach((key, type) -> values.forEach((valueType, value) -> {
            if (!valueType.equals(type)) {
                cases.add(Arguments.of(key, type, value));
            }
        }));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} = {2}")
    @MethodSource("wrongTypes")
    void aValueOfAnotherTypeIsRefusedNamingTheKeyAndTheTypeItTakes(String key, String type, String value)
            throws IOException {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        String path = key.replace("*", "m");
        if (path.endsWith("[0]")) {
            JsonArray list = new JsonArray();
            list.add(JsonParser.parseString(value));
            json = with(path.substring(0, path.length() - 3), list);
        } else if (path.endsWith(".K")) {
            JsonObject map = new JsonObject();
            map.add("K", JsonParser.parseString(value));
            json = with(path.substring(0, path.length() - 2), map);
        } else {
            json = with(path, JsonParser.parseString(value));
        }
        Path config = write(json);
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(path + ": " + value + " is not " + type);
    }

    /**
     * #508: a path key holding a string that is no path on this system is refused in one line naming its key path,
     * where Path.of's own InvalidPathException escaped the load naming no key.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"modes.m.mcpConfigTemplate", "modes.m.serverPlugins[0]", "modes.m.paperCacheSeed",
            "server.paperJar", "server.paperCacheDirs[0]", "gradleWrapperFrom", "paths.agentJar"})
    void aStringThatIsNoPathIsRefusedInOneLineNamingItsKey(String key) throws IOException {
        JsonPrimitive noPath = new JsonPrimitive("a\0b");
        JsonElement value = noPath;
        String holder = key;
        if (key.endsWith("[0]")) {
            JsonArray list = new JsonArray();
            list.add(noPath);
            value = list;
            holder = key.substring(0, key.length() - 3);
        }
        Path config = write(with(holder, value));
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .isInstanceOf(IOException.class)
                .hasMessage("Bad bench config " + config + ": " + key + ": \"a\\u0000b\" is not a path: "
                        + "Nul character not allowed");
    }

    /** Each object of bench.json, by its path in {@link #VALID} ({@code ""} is the file itself), with its keys. */
    private static final Map<String, UserJson.Keys> OBJECTS = Map.of("", BenchConfig.KEYS, "agent",
            BenchConfig.AGENT, "modes.m", BenchConfig.MODE, "server", BenchConfig.SERVER);

    @Test
    void validHoldsExactlyTheKeysOfEachObject() {
        OBJECTS.forEach((path, keys) -> assertThat(object(JsonParser.parseString(VALID).getAsJsonObject(), path)
                .keySet()).as(path).containsExactlyInAnyOrderElementsOf(keys.names()));
        // And every field the config holds is a key of its object, so no read key can be refused.
        for (String field : fields(BenchConfig.class, "")) {
            int dot = field.lastIndexOf('.');
            String object = dot < 0 ? "" : field.substring(0, dot).replace("*", "m");
            assertThat(OBJECTS.get(object).names()).as(field).contains(field.substring(dot + 1));
        }
    }

    /** #502: every key of every object, misspelt, is refused in one line naming its path and the keys there are. */
    static Stream<Arguments> misspeltKeys() {
        List<Arguments> cases = new ArrayList<>();
        OBJECTS.forEach((path, keys) -> keys.names().forEach(key -> cases.add(Arguments.of(path, key + "x", keys))));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("misspeltKeys")
    void aMisspeltKeyAtAnyDepthIsRefusedNamingItsPathAndTheKeys(String path, String key, UserJson.Keys keys)
            throws IOException {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        object(json, path).add(key, new JsonPrimitive(1));
        Path config = write(json);
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .isInstanceOf(IOException.class)
                .hasMessage("Bad bench config " + config + ": " + (path.isEmpty() ? "" : path + ".") + key
                        + ": unknown key; the keys are " + keys.names());
    }

    private static JsonObject object(JsonObject root, String path) {
        JsonObject holder = root;
        for (String part : path.isEmpty() ? new String[0] : path.split("\\.")) {
            holder = holder.getAsJsonObject(part);
        }
        return holder;
    }

    @Test
    void oneProblemIsOneLine() throws IOException {
        Path config = write(with("server.acceptEula", new JsonPrimitive("yes")));
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .hasMessage("Bad bench config " + config + ": server.acceptEula: \"yes\" is not true or false");
    }

    @Test
    void theEmptyRconPasswordSaysWhyBeforeAnyServerStarts() throws IOException {
        // #406: an empty password made the server turn RCON off, and each attempt waited 180 s for it.
        Path config = write(with("server.rconPassword", new JsonPrimitive("")));
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .hasMessageContaining("server.rconPassword: is empty; the server turns RCON off without a password");
    }

    @Test
    void theLongestPasswordTheServerReadsIsAllowed() throws IOException {
        Path config = write(with("server.rconPassword", new JsonPrimitive("x".repeat(1446))));
        assertThatCode(() -> BenchConfig.load(config, home())).doesNotThrowAnyException();
    }

    @Test
    void missingFieldsStillGetTheirDefaults() throws IOException {
        Files.writeString(dir.resolve("bench.json"), "{}");
        BenchConfig config = BenchConfig.load(dir.resolve("bench.json"), home());
        assertThat(config.server().rconPassword()).isEqualTo("msc-bench");
        assertThat(config.server().startTimeoutSeconds()).isEqualTo(180);
        assertThat(config.corePort()).isEqualTo(25590);
    }

    @Test
    void theShippedConfigLoads() {
        Path shipped = RepoFiles.path("shippedConfig", "msc-bench/bench.json");
        assertThatCode(() -> BenchConfig.load(shipped, home())).doesNotThrowAnyException();
    }

    @Test
    void theReadmeDocumentsEveryKey() throws IOException {
        String readme = Files.readString(RepoFiles.path("benchReadme", "msc-bench/README.md"));
        for (String field : fields(BenchConfig.class, "")) {
            assertThat(readme).as("README config table").contains("`" + field.replace("*", "<name>") + "`");
        }
    }

    /** The JSON keys of a config record: nested records by their fields, {@code modes} values as {@code modes.*}. */
    private static Set<String> fields(Class<?> record, String prefix) {
        Set<String> fields = new TreeSet<>();
        for (RecordComponent component : record.getRecordComponents()) {
            String key = prefix + component.getName();
            Class<?> type = component.getType();
            if (type.isRecord()) {
                fields.addAll(fields(type, key + "."));
                continue;
            }
            fields.add(key);
            Type generic = component.getGenericType();
            if (generic instanceof ParameterizedType map && type == Map.class
                    && map.getActualTypeArguments()[1] instanceof Class<?> value && value.isRecord()) {
                fields.addAll(fields(value, key + ".*."));
            }
        }
        return fields;
    }

    /** The Java type of the config field at {@code key}; {@code *} steps into a map's record values. */
    private static Class<?> componentType(String key) {
        Type type = BenchConfig.class;
        for (String part : key.split("\\.")) {
            if (part.equals("*") && type instanceof ParameterizedType map) {
                type = map.getActualTypeArguments()[1];
                continue;
            }
            Class<?> owner = type instanceof Class<?> c ? c : Object.class;
            type = !owner.isRecord() ? Object.class : Stream.of(owner.getRecordComponents()).filter(c -> c.getName().equals(part))
                    .findFirst().map(RecordComponent::getGenericType).orElse(Object.class);
        }
        return type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
    }

    private JsonObject with(String key, JsonElement value) {
        JsonObject root = JsonParser.parseString(VALID).getAsJsonObject();
        String[] parts = key.replace("*", "m").split("\\.");
        JsonObject holder = root;
        for (int i = 0; i < parts.length - 1; i++) {
            holder = holder.getAsJsonObject(parts[i]);
        }
        holder.add(parts[parts.length - 1], value);
        return root;
    }

    private Path write(JsonElement config) throws IOException {
        Files.writeString(dir.resolve("mcp.json"), "{}");
        Files.writeString(dir.resolve("paper.jar"), "jar");
        Files.createDirectories(dir.resolve("wrapper"));
        Files.writeString(dir.resolve("wrapper/gradlew"), "#!/bin/sh\n");
        Path file = dir.resolve("bench.json");
        Files.writeString(file, surrogatesEscaped(config.toString()));
        return file;
    }

    /** A lone surrogate cannot be written as UTF-8, so the file holds it as a JSON escape, as a user would. */
    private static String surrogatesEscaped(String json) {
        StringBuilder out = new StringBuilder(json.length());
        json.chars().forEach(c -> out.append(Character.isSurrogate((char) c)
                ? String.format("\\u%04x", c) : String.valueOf((char) c)));
        return out.toString();
    }

    private Path home() throws IOException {
        return Files.createDirectories(dir.resolve("home"));
    }

    /**
     * The impossible values of a number key (#416): its own, plus a decimal, a number past {@code int} (which
     * getAsInt wrapped into a valid port), a string and null.
     */
    private static Object[] number(String... own) {
        List<Object> values = new ArrayList<>(List.of((Object[]) own));
        values.addAll(List.of("0.5", "1800.9", "4294992872", "99999999999", "1e999", "\"60\"", "null", "true", "[1]"));
        return values.toArray();
    }

    private static void cases(String key, Object... values) {
        List<JsonElement> parsed = new ArrayList<>();
        for (Object value : values) {
            parsed.add(value instanceof JsonElement element ? element : JsonParser.parseString((String) value));
        }
        CASES.put(key, parsed);
    }

    private static JsonElement json(String text) {
        return new JsonPrimitive(text);
    }
}
