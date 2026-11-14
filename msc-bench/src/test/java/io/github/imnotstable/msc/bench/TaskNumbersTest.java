package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #416: every number of a task.json, at the task and in each check type, is read exactly as written. A decimal where a
 * whole number is needed, a number past {@code int}, one outside the key's range, a string and null are refused,
 * naming the file and the key path and showing the value as written; getAsInt had cut and wrapped them.
 */
class TaskNumbersTest {

    /** One task whose checks hold every number key a task.json has, each at a valid value. */
    private static final String VALID = """
            {"category":"code","title":"t","timeoutSeconds":600,"checks":[
              {"type":"command","run":["x"],"timeoutSeconds":60,"expectExit":0},
              {"type":"png","path":"a.png","width":16,"height":16,"maxColors":8,"minOpaqueFraction":0.2,
               "maxOpaqueFraction":0.85,"hue":{"from":330,"to":15,"minFraction":0.5,"minSaturation":0.35}},
              {"type":"zip","path":"a.zip","pngEntries":[{"entry":"a.png","width":16,"height":16,"maxColors":8,
               "minOpaqueFraction":0.2,"maxOpaqueFraction":0.85,"hue":{"from":0,"to":360}}]},
              {"type":"player","jar":"a.jar","expect":["x"],"timeoutSeconds":30},
              {"type":"blocks","blocks":[{"pos":[0,-60,0],"block":"stone"}],
               "regions":[{"from":[0,-60,0],"to":[1,-59,1],"block":"stone"}]}]}
            """;

    /** Values no number key takes: not a number at all, or a number getAsInt read as another one. */
    private static final List<String> NEVER = List.of("\"16\"", "null", "true", "[1]", "{}", "4294992872",
            "-4294967296", "99999999999", "1e999");
    /** Decimals, which a whole-number key refuses. */
    private static final List<String> DECIMALS = List.of("0.5", "16.5", "1800.9", "-0.25");

    /** Each number key, as a path in {@link #VALID}, with the values out of its own range. */
    private static final Map<String, List<String>> KEYS = new LinkedHashMap<>();
    /** The keys that take only whole numbers. */
    private static final Set<String> WHOLE = new TreeSet<>();

    static {
        whole("timeoutSeconds", "0", "-1");
        whole("checks[0].timeoutSeconds", "0", "-600");
        whole("checks[0].expectExit");
        for (String png : List.of("checks[1]", "checks[2].pngEntries[0]")) {
            for (String size : List.of("width", "height", "maxColors")) {
                whole(png + "." + size, "0", "-16");
            }
            for (String fraction : List.of("minOpaqueFraction", "maxOpaqueFraction", "hue.minFraction",
                    "hue.minSaturation")) {
                decimal(png + "." + fraction, "-0.1", "1.5", "100");
            }
            decimal(png + ".hue.from", "-1", "360.5", "720");
            decimal(png + ".hue.to", "-0.5", "361");
        }
        whole("checks[3].timeoutSeconds", "0", "-30");
        for (String pos : List.of("checks[4].blocks[0].pos", "checks[4].regions[0].from", "checks[4].regions[0].to")) {
            whole(pos + "[0]", "30000000", "-30000001");
            whole(pos + "[1]", "2032", "-2033", "320000");
            whole(pos + "[2]", "30000000", "-30000001");
        }
    }

    @TempDir
    Path dir;

    @Test
    void theValidTaskLoadsWithItsNumbersAsWritten() throws IOException {
        TaskDef task = load(JsonParser.parseString(VALID));
        assertThat(task.timeoutSeconds()).isEqualTo(600);
        assertThat(task.checks()).hasSize(5);
    }

    @Test
    void wholeNumbersSpelledAsDecimalsAreTheSameNumbers() throws IOException {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        set(json, "timeoutSeconds", JsonParser.parseString("1.2e3"));
        set(json, "checks[4].blocks[0].pos[1]", JsonParser.parseString("-60.0"));
        assertThat(load(json).timeoutSeconds()).isEqualTo(1200);
    }

    @Test
    void theWorldsEdgesAreValidPositions() {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        set(json, "checks[4].blocks[0].pos", JsonParser.parseString("[29999999,2031,-30000000]"));
        assertThatCode(() -> load(json)).doesNotThrowAnyException();
    }

    static Stream<Arguments> refusedNumbers() {
        List<Arguments> cases = new ArrayList<>();
        KEYS.forEach((key, own) -> {
            List<String> values = new ArrayList<>(NEVER);
            if (WHOLE.contains(key)) {
                values.addAll(DECIMALS);
            }
            values.addAll(own);
            values.forEach(value -> cases.add(Arguments.of(key, value)));
        });
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("refusedNumbers")
    void aNumberNotWrittenAsTheKeyTakesIsRefusedNamingFileKeyAndValue(String key, String value) {
        JsonObject json = JsonParser.parseString(VALID).getAsJsonObject();
        JsonElement element = JsonParser.parseString(value);
        set(json, key, element);
        String written = element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() ? value
                : element.toString();
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IOException.class)
                .hasMessage("Task t: task.json " + key + ": " + written + " is not " + range(key));
    }

    @Test
    void everyNumberAReaderTakesFromATaskIsInTheTable() throws IOException {
        // A key read with UserJson's number reads but missing here would have no refused-value cases.
        Pattern call = Pattern.compile("\\.(?:wholeNumber|number)\\(\\s*\"(\\w+)\"");
        Set<String> read = new TreeSet<>();
        for (String file : RepoFiles.list("benchSources")) {
            if (file.contains("/check/") || file.contains("/task/")) {
                Matcher m = call.matcher(Files.readString(RepoFiles.path("benchSources", file)));
                while (m.find()) {
                    read.add(m.group(1));
                }
            }
        }
        Set<String> leaves = new TreeSet<>();
        KEYS.keySet().forEach(k -> leaves.add(k.replaceAll("\\[\\d+]$", "").replaceAll("^.*\\.", "")));
        assertThat(read).isNotEmpty();
        assertThat(leaves).containsAll(read);
    }

    @Test
    void theBundledTasksStillLoad() throws IOException {
        List<TaskDef> tasks = TaskLoader.loadAll(RepoFiles.dir("bundledTasks", "msc-bench/tasks"));
        assertThat(tasks).isNotEmpty();
        assertThat(tasks).filteredOn(t -> t.id().equals("code-config-motd")).singleElement()
                .extracting(TaskDef::timeoutSeconds).isEqualTo(1200);
    }

    private static String range(String key) {
        String leaf = key.replaceAll("^.*\\.", "");
        if (leaf.endsWith("[1]")) {
            return JsonNumbers.BLOCK_Y.text();
        } else if (leaf.endsWith("]")) {
            return JsonNumbers.BLOCK_XZ.text();
        } else if (leaf.equals("timeoutSeconds")) {
            return JsonNumbers.SECONDS.text();
        } else if (leaf.equals("expectExit")) {
            return JsonNumbers.EXIT_CODE.text();
        } else if (leaf.equals("from") || leaf.equals("to")) {
            return JsonNumbers.HUE.text();
        } else if (leaf.endsWith("Fraction") || leaf.equals("minSaturation")) {
            return JsonNumbers.FRACTION.text();
        }
        return JsonNumbers.POSITIVE.text();
    }

    private TaskDef load(JsonElement json) throws IOException {
        Path task = Files.createDirectories(dir.resolve("t"));
        Files.writeString(task.resolve("task.json"), json.toString());
        Files.writeString(task.resolve("prompt.md"), "Do it.");
        return TaskLoader.load(task);
    }

    /** Sets the value at a path such as {@code checks[1].hue.from} or {@code checks[4].blocks[0].pos[1]}. */
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
            array.set(index, value);
        } else {
            holder.getAsJsonObject().add((String) last, value);
        }
    }

    private static void whole(String key, String... own) {
        KEYS.put(key, List.of(own));
        WHOLE.add(key);
    }

    private static void decimal(String key, String... own) {
        KEYS.put(key, List.of(own));
    }
}
