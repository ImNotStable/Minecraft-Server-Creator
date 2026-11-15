package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #423: the readers of the files a user writes get the file's JSON as a {@link UserJson}, whose only number reads are
 * {@link JsonNumbers}' exact ones, so Gson's cutting {@code getAsInt} is out of their reach by type, not by a scan of
 * their source text (which also failed on {@code OptionalInt.getAsInt()} and comments).
 */
class UserJsonTest {

    /** Every class that reads bench.json or a task.json. */
    private static final List<String> READERS = List.of("task.TaskLoader", "check.Checks", "check.PngRules",
            "check.BlocksCheck", "check.PlayerCheck", "run.BenchConfig", "run.BenchConfigRules");

    private static final UserJson.Keys CHECK = UserJson.Keys.of("hue", "pos", "width");
    private static final UserJson.Keys HUE = UserJson.Keys.of("from");

    private static UserJson task(String json) {
        return UserJson.of(JsonParser.parseString(json).getAsJsonObject(), "task.json ",
                UserJson.Keys.of("checks", "timeoutSeconds"));
    }

    @Test
    void theReadersHoldNoGsonTree() throws ClassNotFoundException {
        List<String> found = new ArrayList<>();
        for (String reader : READERS) {
            found.addAll(gsonTrees(reader, Class.forName("io.github.imnotstable.msc.bench." + reader)));
        }
        assertThat(found).as("a reader holds a Gson tree (JsonElement) and could read a number with Gson's cutting "
                + "getAsInt; take a UserJson, which reads numbers exactly as written").isEmpty();
    }

    /** #435: only a Gson tree is flagged; Gson's other classes cannot read a number from the file. */
    static final class TreeFree {
        private final Gson gson = new Gson();

        IOException notJson(String id, JsonParseException e) {
            return new IOException(id + ": " + e.getMessage() + gson.hashCode(), e);
        }
    }

    static final class HoldsTrees {
        private final JsonElement any;

        HoldsTrees(JsonObject task) {
            any = task;
        }

        int count(JsonArray list) {
            return list.size() + any.hashCode();
        }
    }

    @Test
    void onlyJsonElementsCountAsATree() {
        assertThat(gsonTrees("TreeFree", TreeFree.class)).isEmpty();
        assertThat(gsonTrees("HoldsTrees", HoldsTrees.class)).containsExactlyInAnyOrder("HoldsTrees.any",
                "HoldsTrees.<init>(JsonObject)", "HoldsTrees.count(JsonArray)");
    }

    /** The parameters and fields of {@code type} that hold a Gson tree, named {@code owner.member}. */
    private static List<String> gsonTrees(String owner, Class<?> type) {
        List<String> found = new ArrayList<>();
        for (Executable e : Stream.concat(Stream.of(type.getDeclaredMethods()),
                Stream.of(type.getDeclaredConstructors())).toList()) {
            for (Class<?> parameter : e.getParameterTypes()) {
                if (isGsonTree(parameter)) {
                    String member = e instanceof Constructor<?> ? "<init>" : e.getName();
                    found.add(owner + "." + member + "(" + parameter.getSimpleName() + ")");
                }
            }
        }
        for (Field f : type.getDeclaredFields()) {
            if (isGsonTree(f.getType())) {
                found.add(owner + "." + f.getName());
            }
        }
        return found;
    }

    @Test
    void userJsonHandsOutNoGsonTreeAndJsonNumbersNoPublicRead() {
        List<String> leaks = new ArrayList<>();
        for (Method m : UserJson.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && isGsonTree(m.getReturnType())) {
                leaks.add("UserJson." + m.getName());
            }
        }
        for (Method m : JsonNumbers.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())
                    && Stream.of(m.getParameterTypes()).anyMatch(UserJsonTest::isGsonTree)) {
                leaks.add("JsonNumbers." + m.getName());
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void keysAreNamedByTheirPathInTheFile() {
        UserJson json = task("""
                {"checks":[{},{"hue":{"from":1.5},"pos":[1,2.5,3]}],"timeoutSeconds":0.5}""");
        UserJson check = json.exactObjects("checks", CHECK).get(1);
        assertThat(check.exactObject("hue", HUE).key("from")).isEqualTo("task.json checks[1].hue.from");
        assertThat(check.exactObject("hue", HUE).number("from", JsonNumbers.HUE, 0)).isEqualTo(1.5);
        assertThatThrownBy(() -> json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 0))
                .hasMessage("task.json timeoutSeconds: 0.5 is not " + JsonNumbers.SECONDS.text());
        assertThatThrownBy(() -> check.wholeNumbers("pos", "a position is [x, y, z]",
                JsonNumbers.BLOCK_XZ, JsonNumbers.BLOCK_Y, JsonNumbers.BLOCK_XZ))
                .hasMessage("task.json checks[1].pos[1]: 2.5 is not " + JsonNumbers.BLOCK_Y.text());
        assertThatThrownBy(() -> check.wholeNumbers("hue", "a pair", JsonNumbers.HUE, JsonNumbers.HUE))
                .hasMessage("task.json checks[1].hue: a pair");
        assertThat(json.exactObjects("checks", CHECK).getFirst().wholeNumber("width", JsonNumbers.POSITIVE, 7)).isEqualTo(7);
    }

    /**
     * #502: a reader can read only the keys its object was made with, so a key it reads but forgot to list would be
     * refused in every file that holds it; it fails on the first read, in the tests, instead.
     */
    @Test
    void aReaderCannotReadAKeyItsObjectDoesNotList() {
        UserJson json = task("{\"timeoutSeconds\":5}");
        assertThatThrownBy(() -> json.exactString("title", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a reader reads task.json title, which is not one of its keys [checks, timeoutSeconds]");
        assertThatThrownBy(() -> json.has("title")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UserJson.Keys.byType("type", Map.of("a", UserJson.Keys.of("name"))))
                .hasMessage("the keys of type a must be fixed and hold type");
    }

    /** #653: collecting unknown keys names each one, at any depth, and still reads every known key. */
    @Test
    void collectingUnknownKeysNamesEachAndReadsTheRest() {
        List<String> unknown = new ArrayList<>();
        UserJson json = UserJson.collectingUnknownKeys(JsonParser.parseString(
                "{\"a\":1,\"timeoutSeconds\":5,\"b\":2,\"checks\":[{\"widht\":3,\"width\":4}]}").getAsJsonObject(),
                "task.json ", UserJson.Keys.of("checks", "timeoutSeconds"), unknown);
        assertThat(json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 0)).isEqualTo(5);
        assertThat(json.exactObjects("checks", CHECK).getFirst().wholeNumber("width", JsonNumbers.POSITIVE, 0))
                .isEqualTo(4);
        assertThat(unknown).containsExactly(
                "task.json a: unknown key; the keys are [checks, timeoutSeconds]",
                "task.json b: unknown key; the keys are [checks, timeoutSeconds]",
                "task.json checks[0].widht: unknown key; the keys are [hue, pos, width]");
    }

    /** A Gson tree: {@link JsonElement} or a subtype, by assignability, not by package. */
    private static boolean isGsonTree(Class<?> type) {
        return JsonElement.class.isAssignableFrom(type);
    }
}
