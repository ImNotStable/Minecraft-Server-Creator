package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskLoaderTest {

    @Test
    void bundledTasksLoadAndCoverEveryArea() throws IOException {
        List<TaskDef> tasks = TaskLoader.loadAll(Path.of("tasks"));

        assertThat(tasks).hasSizeBetween(5, 10);
        assertThat(tasks).extracting(TaskDef::category).contains("code", "build", "world", "texture");
        for (TaskDef task : tasks) {
            assertThat(task.prompt()).as(task.id()).isNotBlank();
            assertThat(task.checks()).as(task.id()).isNotEmpty();
            task.fixtures().forEach(f -> assertThat(f).isDirectory());
        }
    }

    @Test
    void worldTasksStartTheServerAndCodeTasksShipTheBuggyFixture() throws IOException {
        List<TaskDef> tasks = TaskLoader.loadAll(Path.of("tasks"));

        assertThat(tasks).filteredOn(t -> t.category().equals("build") || t.category().equals("world"))
                .allMatch(t -> t.server().startBefore() && t.needsServer());
        TaskDef fix = tasks.stream().filter(t -> t.id().equals("code-fix-startup")).findFirst().orElseThrow();
        assertThat(fix.fixtures().getLast()).endsWith(Path.of("code-fix-startup", "fixture"));
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
