package io.github.imnotstable.msc.bench.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

/** Which tasks get the "ports are free" step (#577), and the task.json keys no placeholder is filled in (#578). */
class TaskPortsAndPlaceholdersTest {

    @TempDir
    Path dir;

    private TaskDef task(String json) throws IOException {
        Path task = Files.createDirectories(dir.resolve("t"));
        Files.createDirectories(dir.resolve("shared"));
        Files.createDirectories(task.resolve("hidden"));
        Files.writeString(task.resolve("task.json"), json);
        Files.writeString(task.resolve("prompt.md"), "p");
        return TaskLoader.load(task);
    }

    private static final String FILE = "{\"type\":\"file\",\"path\":\"a\"}";
    private static final String RCON = "{\"type\":\"rcon\",\"command\":\"list\",\"expect\":\"x\"}";
    private static final String PLAYER = "{\"type\":\"player\",\"jar\":\"a.jar\",\"expect\":[\"x\"]}";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        // what                       | platform | scaffold | check  | reset | uses ports | freed before checks
        "texture: files only          | paper    | false    | FILE   | false | false      | false",
        "Paper server, reused by checks | paper  | false    | RCON   | false | true       | false",
        "Paper server with a reset    | paper    | false    | RCON   | true  | true       | true",
        "Minestom, file checks only   | minestom | false    | FILE   | false | true       | true",
        "Minestom with a player check | minestom | false    | PLAYER | false | true       | true",
        "a player check on paper      | paper    | false    | PLAYER | false | true       | true",
        "a scaffolded project         | paper    | true     | FILE   | false | true       | true",
    })
    void everyTaskThatUsesTheBenchPortsGetsThemFree(String what, String platform, boolean scaffold, String check,
            boolean reset, boolean usesPorts, boolean freedBeforeChecks) throws IOException {
        String checkJson = switch (check) {
            case "RCON" -> RCON;
            case "PLAYER" -> PLAYER;
            default -> FILE;
        };
        TaskDef task = task("{\"category\":\"c\",\"title\":\"t\",\"platform\":\"" + platform + "\","
                + (scaffold ? "\"scaffold\":{\"template\":\"minestom-game\",\"name\":\"G\",\"package\":\"a.b\"}," : "")
                + (reset ? "\"resetBeforeChecks\":{\"delete\":[\"x\"]}," : "")
                + "\"checks\":[" + checkJson + "]}");

        assertThat(task.usesBenchPorts()).as(what).isEqualTo(usesPorts);
        assertThat(task.freesPortsBeforeChecks()).as(what).isEqualTo(freedBeforeChecks);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "task.json category    | \"category\":\"c-{scaffoldVersion}\",\"title\":\"t\"",
        "task.json title       | \"category\":\"c\",\"title\":\"for {minecraftVersion}\"",
        "task.json scaffold.name | \"category\":\"c\",\"title\":\"t\",\"scaffold\":{\"template\":\"minestom-game\",\"name\":\"G{scaffoldVersion}\",\"package\":\"a.b\"}",
        "task.json fixtures[0] | \"category\":\"c\",\"title\":\"t\",\"fixtures\":[\"shared-{minecraftVersion}\"]",
        "task.json resetBeforeChecks.delete[0] | \"category\":\"c\",\"title\":\"t\",\"resetBeforeChecks\":{\"delete\":[\"build/libs/g-{scaffoldVersion}.jar\"]}",
    })
    void aPlaceholderInAKeyTheRunnerDoesNotFillIsRefusedNamingTheKey(String key, String fields) {
        assertThatThrownBy(() -> task("{" + fields + ",\"checks\":[" + FILE + "]}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(key + ": ")
                .hasMessageContaining("placeholders are filled only in server.setup and in checks");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "a brace that is no placeholder | {not a name}",
        "a name the runner does not fill | {myPath}",
    })
    void otherBracesInThoseKeysStay(String what, String title) throws IOException {
        assertThat(task("{\"category\":\"c\",\"title\":\"" + title + "\",\"checks\":[" + FILE + "]}").title())
                .as(what).isEqualTo(title);
    }
}
