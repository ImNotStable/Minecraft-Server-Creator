package io.github.imnotstable.msc.bench.task;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.Checks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads tasks from a folder laid out as {@code <tasksDir>/<id>/task.json} plus {@code prompt.md}
 * and an optional {@code fixture/} folder (copied into the workspace). Other folders, such as hidden tests a
 * check adds after the agent is done, stay out of the workspace. Folders starting with {@code _} hold shared fixtures and
 * are not tasks.
 */
public final class TaskLoader {

    private TaskLoader() {
    }

    public static List<TaskDef> loadAll(Path tasksDir) throws IOException {
        if (!Files.isDirectory(tasksDir)) {
            throw new IOException("Tasks folder not found: " + tasksDir.toAbsolutePath());
        }
        try (Stream<Path> dirs = Files.list(tasksDir)) {
            List<Path> taskDirs = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("_"))
                    .filter(d -> Files.isRegularFile(d.resolve("task.json")))
                    .sorted()
                    .toList();
            List<TaskDef> tasks = new ArrayList<>();
            for (Path dir : taskDirs) {
                tasks.add(load(dir));
            }
            return tasks;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static TaskDef load(Path taskDir) throws IOException {
        String id = taskDir.getFileName().toString();
        UserJson json;
        try {
            json = UserJson.of(
                    JsonParser.parseString(Files.readString(taskDir.resolve("task.json"))).getAsJsonObject(),
                    "task.json ");
        } catch (RuntimeException e) {
            throw new IOException("Task " + id + ": task.json is not a JSON object: " + e.getMessage(), e);
        }
        try {
            Path promptFile = taskDir.resolve("prompt.md");
            if (!Files.isRegularFile(promptFile)) {
                throw new IllegalArgumentException("prompt.md is missing");
            }
            String prompt = Files.readString(promptFile).strip();

            List<Path> fixtures = new ArrayList<>();
            for (String shared : json.exactStrings("fixtures")) {
                Path fixture = taskDir.getParent().resolve(shared).normalize();
                if (!Files.isDirectory(fixture)) {
                    throw new IllegalArgumentException("shared fixture not found: " + shared);
                }
                fixtures.add(fixture);
            }
            if (Files.isDirectory(taskDir.resolve("fixture"))) {
                fixtures.add(taskDir.resolve("fixture"));
            }

            String platform = json.exactString("platform", TaskDef.PAPER);
            if (!platform.equals(TaskDef.PAPER) && !platform.equals(TaskDef.MINESTOM)) {
                throw new IllegalArgumentException("platform must be paper or minestom, not " + platform);
            }
            Scaffold scaffold = null;
            UserJson s = json.exactObject("scaffold");
            if (s != null) {
                scaffold = new Scaffold(requireString(s, "template"), requireString(s, "name"), requireString(s, "package"));
            }

            ServerSpec server = ServerSpec.NONE;
            UserJson spec = json.exactObject("server");
            if (spec != null) {
                server = new ServerSpec(spec.exactBool("startBefore", false), spec.exactStrings("setup"));
            }

            List<Check> checks = new ArrayList<>();
            for (UserJson check : json.exactObjects("checks")) {
                checks.add(Checks.parse(check, taskDir));
            }
            if (checks.isEmpty()) {
                throw new IllegalArgumentException("a task needs at least one check");
            }

            return new TaskDef(
                    id,
                    requireString(json, "category"),
                    requireString(json, "title"),
                    platform,
                    prompt,
                    scaffold,
                    fixtures,
                    server,
                    checks,
                    json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 0));
        } catch (RuntimeException e) {
            throw new IOException("Task " + id + ": " + e.getMessage(), e);
        }
    }

    private static String requireString(UserJson json, String key) {
        String value = json.exactString(key, null);
        if (value == null) {
            throw new IllegalArgumentException("missing \"" + key + "\"");
        }
        return value;
    }
}
