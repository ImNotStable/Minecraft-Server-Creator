package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.bench.Quoted;
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
        cases("agent.command", "[]", "[\"\"]", "[null]");
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
        cases("server.downloadsUrl", "\"\"", "\"ftp://host/x\"", "\"not a url\"", "\"https:///nohost\"",
                "\"https://fill.papermc.io?mirror=1\"", "\"https://fill.papermc.io/#top\"", "\"https://h/#/\"");
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

    /** #649: a base URL written with a trailing slash loads without it, as msc-core trims it. */
    @ParameterizedTest
    @ValueSource(strings = {"https://fill.papermc.io/", "https://fill.papermc.io//", "https://fill.papermc.io"})
    void theDownloadsUrlLoadsWithoutItsTrailingSlash(String url) throws IOException {
        BenchConfig config = BenchConfig.load(write(with("server.downloadsUrl", new JsonPrimitive(url))), home());
        assertThat(config.server().downloadsUrl()).isEqualTo("https://fill.papermc.io");
    }

    @Test
    void aMirrorPathKeepsItsPathWithoutTheTrailingSlash() throws IOException {
        JsonPrimitive url = new JsonPrimitive("http://mirror.example/papermc/");
        BenchConfig config = BenchConfig.load(write(with("server.downloadsUrl", url)), home());
        assertThat(config.server().downloadsUrl()).isEqualTo("http://mirror.example/papermc");
    }

    @Test
    void aQueryInTheDownloadsUrlIsRefusedSayingWhy() throws IOException {
        Path config = write(with("server.downloadsUrl", new JsonPrimitive("https://fill.papermc.io/?a=b")));
        assertThatThrownBy(() -> BenchConfig.load(config, home())).hasMessageContaining(
                "server.downloadsUrl: \"https://fill.papermc.io/?a=b\" has a query or fragment");
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
        // #661: a value under a secret key is shown only by its type and length.
        String shown = secret(path) ? Quoted.masked(JsonParser.parseString(value)) : value;
        assertThatThrownBy(() -> BenchConfig.load(config, home()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(path + ": " + shown + " is not " + type);
    }

    /** Whether {@code path} lies under a key bench.json's keys mark secret, as the mode {@code m}. */
    private static boolean secret(String path) {
        return BenchConfig.SERVER.secrets().stream().anyMatch(k -> path.startsWith("server." + k))
                || BenchConfig.MODE.secrets().stream().anyMatch(k -> path.equals("modes.m." + k)
                        || path.startsWith("modes.m." + k + "."));
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

    /** #652: a refused downloadsUrl is quoted as the file writes it, never as the trimmed value the run would use. */
    static Stream<Arguments> downloadsUrlsAsWritten() {
        return Stream.of(
                Arguments.of("/", "server.downloadsUrl: \"/\" is no http or https URL"),
                Arguments.of("https://h/#/", "server.downloadsUrl: \"https://h/#/\" has a query or fragment; give the "
                        + "service's base URL, such as https://fill.papermc.io"),
                Arguments.of("http://my_host:8080/", "server.downloadsUrl: \"http://my_host:8080/\" is no http or https URL"),
                Arguments.of("https://h/?a=b/", "server.downloadsUrl: \"https://h/?a=b/\" has a query or fragment; give "
                        + "the service's base URL, such as https://fill.papermc.io"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("downloadsUrlsAsWritten")
    void aRefusedDownloadsUrlIsQuotedAsWritten(String url, String problem) throws IOException {
        assertThat(problems("{\"server\":{\"downloadsUrl\":" + json(url) + "}}")).containsExactly(problem);
    }

    /** #652: a path that is no file is quoted as written, then named where it was looked for. */
    @Test
    void aMissingFileIsQuotedAsWrittenAndNamedWhereItWasLookedFor() throws IOException {
        List<String> problems = problems("""
                {"server":{"paperJar":"jars/../missing.jar"},"gradleWrapperFrom":"no-wrapper",
                 "modes":{"m":{"mcpConfigTemplate":"./missing.json","serverPlugins":["lib/{nothing}.jar"]}}}""");
        assertThat(problems).containsExactly(
                "modes.m.mcpConfigTemplate: no such file \"./missing.json\" (" + dir.resolve("missing.json") + ")",
                "modes.m.serverPlugins: {nothing} in \"lib/{nothing}.jar\" is no placeholder; name a key of paths, "
                        + "such as {agentJar}",
                "server.paperJar: no such file \"jars/../missing.jar\" (" + dir.resolve("missing.jar") + ")",
                "gradleWrapperFrom: \"no-wrapper\" (" + dir.resolve("no-wrapper") + ") has no gradlew, so Gradle "
                        + "workspaces would get no wrapper");
    }

    /**
     * #653: problems found by the typed reads or the rules on values as written no longer hide those of the filled-in
     * config; every one is listed in one refusal. The first three are the cases of the issue.
     */
    static Stream<Arguments> problemsOfEveryPhase() {
        return Stream.of(
                Arguments.of("{\"server\":{\"paperJar\":\"\",\"downloadsUrl\":\"ftp://x\"}}", List.of(
                        "server.paperJar: is blank; leave it out or give a value",
                        "server.downloadsUrl: \"ftp://x\" is no http or https URL")),
                Arguments.of("{\"corePort\":\"x\",\"server\":{\"rconPassword\":\"\"}}", List.of(
                        "corePort: \"x\" is not a TCP port, a whole number from 1 to 65535",
                        "server.rconPassword: is empty; the server turns RCON off without a password, so leave it out "
                                + "for the default or set one")),
                Arguments.of("{\"server\":{\"paperCacheDirs\":[\"\"],\"rconPort\":25566}}", List.of(
                        "server.paperCacheDirs: entry 0 is blank",
                        "server.serverPort: 25566 is also server.rconPort; each needs its own port")),
                Arguments.of("""
                        {"agnt":{},"agent":{"timeoutSecond":5,"command":[]},"server":{"acceptEula":"yes",
                         "paperJar":"missing.jar","serverPort":25590},"modes":{"m":null,"n":{"serverPlugins":["{x}"]}},
                         "gradleWrapperFrom":" "}""", List.of(
                        "agnt: unknown key; the keys are " + BenchConfig.KEYS.names(),
                        "agent.timeoutSecond: unknown key; the keys are " + BenchConfig.AGENT.names(),
                        "server.acceptEula: \"yes\" is not true or false",
                        "agent.command: is empty; leave it out for the default or name a program",
                        "modes.m: is null; use {} for a mode with no settings",
                        "gradleWrapperFrom: is blank; leave it out or give a value",
                        "modes.n.serverPlugins: {x} in \"{x}\" is no placeholder; name a key of paths, such as {agentJar}",
                        "server.paperJar: no such file \"missing.jar\" (<dir>/missing.jar)",
                        "corePort: 25590 is also server.serverPort; each needs its own port")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("problemsOfEveryPhase")
    void everyProblemIsListedInOneRefusal(String config, List<String> expected) throws IOException {
        String missing = dir.resolve("missing.jar").toString();
        assertThat(problems(config)).containsExactlyElementsOf(
                expected.stream().map(p -> p.replace("<dir>/missing.jar", missing)).toList());
    }

    /**
     * #653: what the filled-in config's rules leave out is only what depends on a value refused already, so no
     * refusal names a value the file does not hold, such as a default port it would clash with.
     */
    static Stream<Arguments> rulesThatDependOnARefusedValue() {
        return Stream.of(
                // corePort's default, 25590, would clash: the refused corePort is not checked against it.
                Arguments.of("{\"corePort\":\"x\",\"server\":{\"serverPort\":25590}}",
                        "corePort: \"x\" is not a TCP port, a whole number from 1 to 65535"),
                Arguments.of("{\"corePort\":25576,\"server\":{\"rconPort\":1.5}}",
                        "server.rconPort: 1.5 is not a TCP port, a whole number from 1 to 65535"),
                // server's default ports are not checked against corePort while server cannot be read.
                Arguments.of("{\"corePort\":25566,\"server\":\"yes\"}", "server: \"yes\" is not an object"),
                // {agentJar} cannot be checked against paths that cannot be read.
                Arguments.of("{\"paths\":[],\"modes\":{\"m\":{\"serverPlugins\":[\"{agentJar}\"]}}}",
                        "paths: [] is not an object"),
                // a null path still names a placeholder; a blank file path is not looked for on disk.
                Arguments.of("{\"paths\":{\"agentJar\":null},\"modes\":{\"m\":{\"serverPlugins\":[\"{agentJar}\"]}}}",
                        "paths.agentJar: is null; leave it out or give a value"),
                Arguments.of("{\"modes\":{\"m\":{\"mcpConfigTemplate\":\"\"}}}",
                        "modes.m.mcpConfigTemplate: is blank; leave it out or give a value"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rulesThatDependOnARefusedValue")
    void aRuleThatDependsOnARefusedValueIsLeftOut(String config, String only) throws IOException {
        assertThat(problems(config)).containsExactly(only);
    }

    /**
     * #661: a secret of the wrong type, the rows of the issue, is refused naming its key and showing only the value's
     * type and length; so is an env name written {@code KEY=value}, shown up to its {@code =}.
     */
    static Stream<Arguments> secretsOfTheWrongType() {
        return Stream.of(
                Arguments.of("{\"server\":{\"rconPassword\":12345678}}", "12345678",
                        "server.rconPassword: a number of 8 characters is not a string"),
                Arguments.of("{\"server\":{\"rconPassword\":[\"hunter2\"]}}", "hunter2",
                        "server.rconPassword: a list of 1 entry is not a string"),
                Arguments.of("{\"server\":{\"rconPassword\":{\"x\":\"hunter2\"}}}", "hunter2",
                        "server.rconPassword: an object of 1 key is not a string"),
                Arguments.of("{\"modes\":{\"m\":{\"env\":{\"ANTHROPIC_API_KEY\":[\"sk-ant-secret\"]}}}}", "sk-ant-secret",
                        "modes.m.env.ANTHROPIC_API_KEY: a list of 1 entry is not a string"),
                Arguments.of("{\"modes\":{\"m\":{\"env\":\"ANTHROPIC_API_KEY=sk-ant-secret\"}}}", "sk-ant-secret",
                        "modes.m.env: a string of 31 characters is not an object"),
                Arguments.of("{\"modes\":{\"m\":{\"serverEnv\":{\"TOKEN\":42424242}}}}", "42424242",
                        "modes.m.serverEnv.TOKEN: a number of 8 characters is not a string"),
                Arguments.of("{\"modes\":{\"m\":{\"env\":{\"API_KEY=sk-ant-secret\":\"x\"}}}}", "sk-ant-secret",
                        "modes.m.env: \"API_KEY=\"<13 hidden characters> is no environment variable name"),
                Arguments.of("{\"modes\":{\"m\":{\"env\":{\"API_KEY=sk-ant-secret\":null}}}}", "sk-ant-secret",
                        "modes.m.env: \"API_KEY=\"<13 hidden characters> is no environment variable name\n"
                                + "modes.m.env.\"API_KEY=\"<13 hidden characters>: is null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secretsOfTheWrongType")
    void aSecretIsRefusedShowingOnlyItsTypeAndLength(String config, String secret, String expected)
            throws IOException {
        List<String> problems = problems(config);
        assertThat(problems).containsExactly(expected.split("\n"));
        assertThat(String.join("\n", problems)).doesNotContain(secret);
    }

    /**
     * #662: each value a refusal shows goes through the one quoting, so a newline or a space at an end is shown
     * escaped in quotes, on one line. The first five are the rows of the issue.
     */
    static Stream<Arguments> valuesShownQuoted() {
        return Stream.of(
                Arguments.of("{\"minecraftVersion\":\"26.3\\n\"}",
                        "minecraftVersion: \"26.3\\n\" is no Minecraft version, such as 26.3"),
                Arguments.of("{\"server\":{\"downloadsUrl\":\"https://x/\\nfoo\"}}",
                        "server.downloadsUrl: \"https://x/\\nfoo\" is no http or https URL"),
                // The text after "=" in an env name may be a secret, so the name is shown up to it.
                Arguments.of("{\"modes\":{\"m\":{\"env\":{\"A\\n=B\":\"1\"}}}}",
                        "modes.m.env: \"A\\n=\"<1 hidden character> is no environment variable name"),
                Arguments.of("{\"paths\":{\"a\\nb\":\"x\"}}",
                        "paths: the name \"a\\nb\" is no placeholder name; use letters, digits, '.', '_' or '-'"),
                Arguments.of("{\"modes\":{\"m\":{\"paperCacheSeed\":\"../x \"}}}",
                        "modes.m.paperCacheSeed: \"../x \" is not a folder inside the workspace"),
                Arguments.of("{\"modes\":{\"a\\nb\":{}}}", "modes.\"a\\nb\": the name is no mode name (it names "
                        + "attempt folders and --modes lists); use letters, digits, '.', '_' or '-'"),
                Arguments.of("{\"agent\":{\"x\\u2028y \":1}}",
                        "agent.\"x\\u2028y \": unknown key; the keys are [command, timeoutSeconds]"),
                Arguments.of("{\"server\":{\"downloadsUrl\":\"\\u202ehttps://x\"}}",
                        "server.downloadsUrl: \"\\u202ehttps://x\" is no http or https URL"),
                Arguments.of("{\"modes\":{\"m\":{\"serverPlugins\":[\"{a\\nb}\"]}}}",
                        "modes.m.serverPlugins: \"{a\\nb}\" in \"{a\\nb}\" is no placeholder; name a key of paths, "
                                + "such as {agentJar}"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("valuesShownQuoted")
    void aValueIsShownQuotedOnOneLine(String config, String expected) throws IOException {
        assertThat(problems(config)).containsExactly(expected);
    }

    /** #662: a missing path at run is quoted as written, then named where it was looked for. */
    @Test
    void aMissingPathAtRunIsQuotedAsWrittenThenResolved() throws IOException {
        Path config = dir.resolve("bench.json");
        Files.writeString(config, "{\"paths\":{\"agentJar\":\"build/../no.jar\"},"
                + "\"modes\":{\"m\":{\"serverPlugins\":[\"{agentJar}\"]}}}");
        BenchConfig loaded = BenchConfig.load(config, home());
        assertThatThrownBy(() -> loaded.requirePaths(config, List.of("m"))).isInstanceOf(IOException.class)
                .hasMessage("Bad bench config " + config + ": paths.agentJar: no such file or folder "
                        + "\"build/../no.jar\" (" + dir.resolve("no.jar") + ") (used by modes.m.serverPlugins); run "
                        + "./gradlew :msc-agent-paper:shadowJar first or fix the path");
    }

    /**
     * #663: one refused entry of a list or object no longer hides the others; the first four are the rows of the
     * issue. A refused paths entry leaves out only the placeholders that name it.
     */
    static Stream<Arguments> entriesCheckedOneByOne() {
        return Stream.of(
                Arguments.of("{\"modes\":{\"m\":{\"env\":{\"PORT\":8080,\"B=\":\"x\"}}}}", List.of(
                        "modes.m.env.PORT: a number of 4 characters is not a string",
                        "modes.m.env: \"B=\"<0 hidden characters> is no environment variable name")),
                Arguments.of("{\"paths\":{\"bad name\":\"x\",\"a\":\"foo\\u0000\"}}", List.of(
                        "paths.a: \"foo\\u0000\" is not a path: ",
                        "paths: the name \"bad name\" is no placeholder name; use letters, digits, '.', '_' or '-'")),
                Arguments.of("{\"agent\":{\"command\":[5,\"\"]}}", List.of(
                        "agent.command[0]: 5 is not a string",
                        "agent.command: entry 1 is blank")),
                Arguments.of("{\"server\":{\"paperCacheDirs\":[\"a\\u0000\",\"\"]}}", List.of(
                        "server.paperCacheDirs[0]: \"a\\u0000\" is not a path: ",
                        "server.paperCacheDirs: entry 1 is blank")),
                Arguments.of("{\"paths\":{\"a\":5},\"modes\":{\"m\":{\"serverPlugins\":[\"{a}\",\"{typo}\"]}}}", List.of(
                        "paths.a: 5 is not a string",
                        "modes.m.serverPlugins: {typo} in \"{typo}\" is no placeholder; name a key of paths, such as "
                                + "{agentJar}")),
                Arguments.of("{\"modes\":{\"x\":5,\"y\":{\"extraArgs\":[null,true]}}}", List.of(
                        "modes.x: 5 is not an object",
                        "modes.y.extraArgs[1]: true is not a string",
                        "modes.y.extraArgs: entry 0 is null")),
                Arguments.of("{\"corePort\":1,\"corePort\":2,\"server\":{\"rconPort\":\"x\",\"rconPort\":\"y\"}}", List.of(
                        "corePort: given twice; give each key once",
                        "server.rconPort: given twice; give each key once",
                        "server.rconPort: \"y\" is not a TCP port, a whole number from 1 to 65535")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entriesCheckedOneByOne")
    void everyEntryOfAListOrObjectIsCheckedOnItsOwn(String config, List<String> expected) throws IOException {
        Path file = dir.resolve("bench.json");
        Files.writeString(file, config);
        String message = catchThrowable(() -> BenchConfig.load(file, home())).getMessage();
        String head = "Bad bench config " + file + ":\n  ";
        assertThat(message).startsWith(head);
        List<String> problems = List.of(message.substring(head.length()).split("\n  "));
        assertThat(problems).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(problems.get(i)).startsWith(expected.get(i));
        }
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

    /** #519: run needs the paths its selected modes use; a mode not selected, or a path that exists, is no problem. */
    @Test
    void onlyTheSelectedModesNeedTheirPaths() throws IOException {
        Path shipped = RepoFiles.path("shippedConfig", "msc-bench/bench.json");
        BenchConfig loaded = BenchConfig.load(shipped, home());
        BenchConfig unbuilt = new BenchConfig(loaded.agent(), loaded.modes(), loaded.server(),
                loaded.gradleWrapperFrom(), Map.of("coreLib", dir.resolve("no-lib").toString(), "agentJar",
                        dir.resolve("no.jar").toString()), loaded.corePort(), loaded.minecraftVersion());

        assertThat(BenchConfigRules.missingPaths(unbuilt, null, List.of("baseline"))).isEmpty();
        assertThat(BenchConfigRules.missingPaths(unbuilt, null, List.of("toolset", "baseline"))).containsExactly(
                "paths.coreLib: no such file or folder " + dir.resolve("no-lib") + " (used by "
                        + "modes.toolset.mcpConfigTemplate); run ./gradlew :msc-core:installDist first or fix the path",
                "paths.agentJar: no such file or folder " + dir.resolve("no.jar") + " (used by "
                        + "modes.toolset.mcpConfigTemplate); run ./gradlew :msc-agent-paper:shadowJar first or fix "
                        + "the path");
        Files.createDirectories(dir.resolve("no-lib"));
        Files.writeString(dir.resolve("no.jar"), "jar");
        assertThat(BenchConfigRules.missingPaths(unbuilt, null, List.of("toolset"))).isEmpty();
    }

    @Test
    void theShippedConfigLoads() {
        Path shipped = RepoFiles.path("shippedConfig", "msc-bench/bench.json");
        assertThatCode(() -> BenchConfig.load(shipped, home())).doesNotThrowAnyException();
    }

    /** #624: the shipped agent gets its prompt on stdin in every mode, never on a Windows command line. */
    @Test
    void theShippedAndDefaultAgentsReadThePromptOnStdin() throws IOException {
        BenchConfig shipped = BenchConfig.load(RepoFiles.path("shippedConfig", "msc-bench/bench.json"), home());
        assertThat(shipped.modes()).isNotEmpty();
        assertThat(shipped.modes().values()).allSatisfy(mode -> assertThat(shipped.promptOnStdin(mode)).isTrue());
        assertThat(BenchConfig.defaults().agent().command()).doesNotContain("{prompt}");
    }

    /** A {prompt} in the command, a mode's extraArgs or its env puts the prompt there, and stdin stays empty. */
    @Test
    void aPromptPlaceholderAnywhereKeepsThePromptOffStdin() throws IOException {
        BenchConfig.Mode plain = new BenchConfig.Mode(null, null, null, null, null, null, null);
        BenchConfig.Mode inArgs = new BenchConfig.Mode(List.of("--append", "x{prompt}"), null, null, null, null,
                null, null);
        BenchConfig.Mode inEnv = new BenchConfig.Mode(null, Map.of("TASK", "{prompt}"), null, null, null, null, null);
        BenchConfig loaded = BenchConfig.load(write(JsonParser.parseString(VALID)), home());
        BenchConfig onStdin = new BenchConfig(new BenchConfig.Agent(List.of("claude", "-p"), 60), loaded.modes(),
                loaded.server(), loaded.gradleWrapperFrom(), loaded.paths(), loaded.corePort(),
                loaded.minecraftVersion());

        assertThat(loaded.promptOnStdin(plain)).isFalse();
        assertThat(onStdin.promptOnStdin(plain)).isTrue();
        assertThat(onStdin.promptOnStdin(inArgs)).isFalse();
        assertThat(onStdin.promptOnStdin(inEnv)).isFalse();
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

    /** The problems {@code BenchConfig.load} lists for the config {@code json}, one per entry. */
    private List<String> problems(String json) throws IOException {
        Path config = write(JsonParser.parseString(json));
        Throwable refusal = catchThrowable(() -> BenchConfig.load(config, home()));
        assertThat(refusal).isInstanceOf(IOException.class);
        String head = "Bad bench config " + config + ":";
        assertThat(refusal.getMessage()).startsWith(head);
        String rest = refusal.getMessage().substring(head.length());
        return rest.startsWith("\n  ") ? List.of(rest.substring(3).split("\n  ")) : List.of(rest.substring(1));
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
