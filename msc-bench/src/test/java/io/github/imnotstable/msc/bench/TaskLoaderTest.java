package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskLoaderTest {

    /** The bundled tasks: the build's bundledTasks set, so editing a task reruns these tests. */
    private static final Path BUNDLED = RepoFiles.dir("bundledTasks", "msc-bench/tasks");

    @Test
    void bundledTasksLoadAndCoverEveryArea() throws IOException {
        List<TaskDef> tasks = TaskLoader.loadAll(BUNDLED);

        assertThat(tasks).hasSize(9);
        assertThat(tasks).extracting(TaskDef::category).contains("code", "build", "world", "texture");
        for (TaskDef task : tasks) {
            assertThat(task.prompt()).as(task.id()).isNotBlank();
            assertThat(task.checks()).as(task.id()).isNotEmpty();
            task.fixtures().forEach(f -> assertThat(f).isDirectory());
        }
    }

    @Test
    void worldTasksStartTheServerAndCodeTasksShipTheBuggyFixture() throws IOException {
        List<TaskDef> tasks = TaskLoader.loadAll(BUNDLED);

        assertThat(tasks).filteredOn(t -> t.category().equals("build") || t.category().equals("world"))
                .allMatch(t -> t.server().startBefore() && t.needsServer());
        // #495: the motd checks ask for the shipped default, so the config the agent's testing saved is reset.
        TaskDef motd = tasks.stream().filter(t -> t.id().equals("code-config-motd")).findFirst().orElseThrow();
        assertThat(motd.reset().delete()).containsExactly("server/plugins/BenchPlugin/config.yml");
        assertThat(tasks).filteredOn(t -> !t.id().equals("code-config-motd")).allMatch(t -> t.reset().isEmpty());
        TaskDef fix = tasks.stream().filter(t -> t.id().equals("code-fix-startup")).findFirst().orElseThrow();
        assertThat(fix.fixtures().getLast()).endsWith(Path.of("code-fix-startup", "fixture"));
    }

    @Test
    void theReadmeCountsTheBlockPositionsEachTaskChecks() throws IOException {
        // #395: the README said 196 for build-hut, which checks 245.
        String readme = Files.readString(RepoFiles.path("benchReadme", "msc-bench/README.md"));
        Matcher row = Pattern.compile("(?m)^\\| `([a-z0-9-]+)` \\|[^|]*\\| (\\d+) block positions").matcher(readme);
        int rows = 0;
        while (row.find()) {
            rows++;
            assertThat(Integer.parseInt(row.group(2))).as(row.group(1))
                    .isEqualTo(blockPositions(BUNDLED.resolve(row.group(1)).resolve("task.json")));
        }
        assertThat(rows).isPositive();
    }

    /** Every position the task's blocks checks test: single blocks plus the volume of each region. */
    private static int blockPositions(Path taskJson) throws IOException {
        int count = 0;
        for (JsonElement c : JsonParser.parseString(Files.readString(taskJson)).getAsJsonObject().getAsJsonArray("checks")) {
            JsonObject check = c.getAsJsonObject();
            if (!check.get("type").getAsString().equals("blocks")) {
                continue;
            }
            if (check.has("blocks")) {
                count += check.getAsJsonArray("blocks").size();
            }
            if (check.has("regions")) {
                for (JsonElement r : check.getAsJsonArray("regions")) {
                    JsonArray from = r.getAsJsonObject().getAsJsonArray("from");
                    JsonArray to = r.getAsJsonObject().getAsJsonArray("to");
                    int volume = 1;
                    for (int i = 0; i < 3; i++) {
                        volume *= Math.abs(to.get(i).getAsInt() - from.get(i).getAsInt()) + 1;
                    }
                    count += volume;
                }
            }
        }
        return count;
    }

    @Test
    void reportsWhichTaskIsBroken(@TempDir Path dir) throws IOException {
        Path task = Files.createDirectories(dir.resolve("broken"));
        Files.writeString(task.resolve("task.json"), "{\"category\":\"code\",\"title\":\"x\",\"checks\":[]}");
        Files.writeString(task.resolve("prompt.md"), "do it");

        assertThatThrownBy(() -> TaskLoader.loadAll(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("at least one check");
    }

    @Test
    void skipsSharedFixtureFolders(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("_fixtures/x"));
        Path task = Files.createDirectories(dir.resolve("t"));
        Files.writeString(task.resolve("task.json"),
                "{\"category\":\"code\",\"title\":\"x\",\"fixtures\":[\"_fixtures/x\"],"
                        + "\"checks\":[{\"type\":\"file\",\"path\":\"a\"}]}");
        Files.writeString(task.resolve("prompt.md"), "do it");

        assertThat(TaskLoader.loadAll(dir)).singleElement()
                .satisfies(t -> assertThat(t.fixtures()).containsExactly(dir.resolve("_fixtures/x")));
    }
}
