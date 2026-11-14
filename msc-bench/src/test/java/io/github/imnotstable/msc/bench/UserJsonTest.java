package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonParser;
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
            "check.BlocksCheck", "check.PlayerCheck", "run.BenchConfigRules");

    private static UserJson task(String json) {
        return UserJson.of(JsonParser.parseString(json).getAsJsonObject(), "task.json ");
    }

    @Test
    void theReadersHoldNoGsonTree() throws ClassNotFoundException {
        List<String> found = new ArrayList<>();
        for (String reader : READERS) {
            Class<?> type = Class.forName("io.github.imnotstable.msc.bench." + reader);
            for (Executable e : Stream.concat(Stream.of(type.getDeclaredMethods()),
                    Stream.of(type.getDeclaredConstructors())).toList()) {
                for (Class<?> parameter : e.getParameterTypes()) {
                    if (isGson(parameter)) {
                        found.add(reader + "." + e.getName() + "(" + parameter.getSimpleName() + ")");
                    }
                }
            }
            for (Field f : type.getDeclaredFields()) {
                if (isGson(f.getType())) {
                    found.add(reader + "." + f.getName());
                }
            }
        }
        assertThat(found).as("take a UserJson, which reads numbers exactly as written").isEmpty();
    }

    @Test
    void userJsonHandsOutNoGsonTreeAndJsonNumbersNoPublicRead() {
        List<String> leaks = new ArrayList<>();
        for (Method m : UserJson.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && isGson(m.getReturnType())) {
                leaks.add("UserJson." + m.getName());
            }
        }
        for (Method m : JsonNumbers.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())
                    && Stream.of(m.getParameterTypes()).anyMatch(UserJsonTest::isGson)) {
                leaks.add("JsonNumbers." + m.getName());
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void keysAreNamedByTheirPathInTheFile() {
        UserJson json = task("""
                {"checks":[{},{"hue":{"from":1.5},"pos":[1,2.5,3]}],"timeoutSeconds":0.5}""");
        UserJson check = json.objects("checks").get(1);
        assertThat(check.object("hue").key("from")).isEqualTo("task.json checks[1].hue.from");
        assertThat(check.object("hue").number("from", JsonNumbers.HUE, 0)).isEqualTo(1.5);
        assertThatThrownBy(() -> json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 0))
                .hasMessage("task.json timeoutSeconds: 0.5 is not " + JsonNumbers.SECONDS.text());
        assertThatThrownBy(() -> check.wholeNumbers("pos", "a position is [x, y, z]",
                JsonNumbers.BLOCK_XZ, JsonNumbers.BLOCK_Y, JsonNumbers.BLOCK_XZ))
                .hasMessage("task.json checks[1].pos[1]: 2.5 is not " + JsonNumbers.BLOCK_Y.text());
        assertThatThrownBy(() -> check.wholeNumbers("hue", "a pair", JsonNumbers.HUE, JsonNumbers.HUE))
                .hasMessage("task.json checks[1].hue: a pair");
        assertThat(json.objects("checks").getFirst().wholeNumber("width", JsonNumbers.POSITIVE, 7)).isEqualTo(7);
    }

    record Bound(Inner server, int corePort) {
        record Inner(int rconPort, int serverPort) {
        }
    }

    @Test
    void bindChecksNumbersAsWrittenAndBindsTheCheckedOnes() {
        UserJson json = UserJson.of(JsonParser.parseString("""
                {"server":{"rconPort":2.5e4,"serverPort":4294992872},"corePort":25576}""").getAsJsonObject(), "");
        List<String> problems = new ArrayList<>();
        Bound bound = json.bind(Bound.class,
                Map.of("server.rconPort", JsonNumbers.PORT, "server.serverPort", JsonNumbers.PORT,
                        "corePort", JsonNumbers.PORT), problems);
        // 4294992872 is refused as written, not wrapped to 25576; the refused key is left unbound.
        assertThat(problems).containsExactly("server.serverPort: 4294992872 is not " + JsonNumbers.PORT.text());
        assertThat(bound).isEqualTo(new Bound(new Bound.Inner(25000, 0), 25576));
    }

    private static boolean isGson(Class<?> type) {
        return type.getName().startsWith("com.google.gson.");
    }
}
