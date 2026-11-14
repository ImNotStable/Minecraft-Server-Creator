package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A tripwire, not a proof (#416): msc-bench reads a number from a file a user writes (bench.json, task.json) only
 * through {@link JsonNumbers}, since Gson's getAsInt cuts decimals and wraps big numbers into valid-looking ones, and
 * binding a JSON tree with Gson reads int fields the same way. A file that reads numbers some other way must be one
 * of the readers below of data that msc-bench, msc-core or another program writes, never a user.
 */
class JsonNumbersGuardTest {

    private static final String SET = "benchSources";
    private static final String ROOT = "msc-bench/src/main/java/io/github/imnotstable/msc/bench/";

    /** Gson's number getters, which cut and wrap instead of refusing. */
    private static final Pattern NUMBER_READ = Pattern.compile(
            "\\.\\s*getAs(?:Int|Long|Double|Float|Short|Byte|Number|BigDecimal|BigInteger)\\s*\\(");
    /** Gson binding, which reads an int field of a tree with getAsInt. */
    private static final Pattern BINDING = Pattern.compile("\\.\\s*fromJson\\s*\\(");

    /** The files allowed to read numbers past JsonNumbers, each with why. */
    private static final Map<String, String> NUMBER_READERS = Map.of(
            ROOT + "JsonNumbers.java", "the helper itself",
            ROOT + "run/ToolLogReader.java", "msc-core's tool log, written by msc-core",
            ROOT + "run/ClaudeResultParser.java", "Claude Code's JSON output",
            ROOT + "run/PaperJars.java", "PaperMC's downloads API build ids");
    private static final Map<String, String> BINDERS = Map.of(
            ROOT + "run/BenchConfig.java", "binds after BenchConfigRules.numbers wrote each number key back as checked",
            ROOT + "report/ReportWriter.java", "results.jsonl, written by the bench itself, bound from text");

    @Test
    void thePatternsFlagNumberReadsAndNothingElse() {
        List<String> mustFlag = List.of("o.get(\"x\").getAsInt()", "e.getAsLong ()", "h.get(\"from\").getAsDouble()",
                "p.getAsNumber()", "json.get(\"a\")\n        .getAsInt()", "x.getAsBigDecimal()", "x.getAsFloat()");
        List<String> mustNotFlag = List.of("o.get(\"x\").getAsString()", "o.getAsBoolean()",
                "json.getAsJsonObject(\"server\")", "json.getAsJsonArray(\"checks\")",
                "JsonNumbers.wholeNumber(json, \"width\", key, JsonNumbers.POSITIVE, 0)", "getAsIntent()");
        mustFlag.forEach(code -> assertThat(NUMBER_READ.matcher(code).find()).as(code).isTrue());
        mustNotFlag.forEach(code -> assertThat(NUMBER_READ.matcher(code).find()).as(code).isFalse());
        assertThat(BINDING.matcher("new Gson().fromJson(json, BenchConfig.class)").find()).isTrue();
        assertThat(BINDING.matcher("gson.toJson(record)").find()).isFalse();
    }

    @Test
    void numbersAreReadOnlyThroughJsonNumbers() throws IOException {
        List<String> files = RepoFiles.list(SET);
        assertThat(files).contains(ROOT + "run/BenchConfig.java", ROOT + "task/TaskLoader.java");
        List<String> found = new ArrayList<>();
        TreeSet<String> readers = new TreeSet<>();
        TreeSet<String> binders = new TreeSet<>();
        for (String file : files) {
            String code = Files.readString(RepoFiles.path(SET, file));
            if (find(NUMBER_READ, code, file, NUMBER_READERS, found)) {
                readers.add(file);
            }
            if (find(BINDING, code, file, BINDERS, found)) {
                binders.add(file);
            }
        }
        assertThat(found).as("read these numbers with JsonNumbers, or allow the file with a reason").isEmpty();
        // An entry that no longer reads numbers goes, so the list stays the true set.
        assertThat(readers).as("stale NUMBER_READERS").containsAll(
                NUMBER_READERS.keySet().stream().filter(f -> !f.endsWith("JsonNumbers.java")).toList());
        assertThat(binders).as("stale BINDERS").containsExactlyInAnyOrderElementsOf(BINDERS.keySet());
    }

    /** Adds each match in a file not allowed; true when the file matches at all. */
    private static boolean find(Pattern pattern, String code, String file, Map<String, String> allowed,
            List<String> found) {
        Matcher m = pattern.matcher(code);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (!allowed.containsKey(file)) {
                int line = 1 + (int) code.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                found.add(file + ":" + line + " " + m.group().strip());
            }
        }
        return any;
    }
}
