package io.github.imnotstable.msc.bench.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * and an optional {@code fixture/} folder. Folders starting with {@code _} hold shared fixtures and
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
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(taskDir.resolve("task.json"))).getAsJsonObject();
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
            for (String shared : strings(json, "fixtures")) {
                Path fixture = taskDir.getParent().resolve(shared).normalize();
                if (!Files.isDirectory(fixture)) {
                    throw new IllegalArgumentException("shared fixture not found: " + shared);
                }
                fixtures.add(fixture);
            }
            if (Files.isDirectory(taskDir.resolve("fixture"))) {
                fixtures.add(taskDir.resolve("fixture"));
            }

            ServerSpec server = ServerSpec.NONE;
            if (json.has("server")) {
                JsonObject s = json.getAsJsonObject("server");
                server = new ServerSpec(
                        s.has("startBefore") && s.get("startBefore").getAsBoolean(), strings(s, "setup"));
            }

            List<Check> checks = new ArrayList<>();
            JsonArray checkArray = json.has("checks") ? json.getAsJsonArray("checks") : new JsonArray();
            for (JsonElement element : checkArray) {
                checks.add(Checks.parse(element.getAsJsonObject()));
            }
            if (checks.isEmpty()) {
                throw new IllegalArgumentException("a task needs at least one check");
            }

            return new TaskDef(
                    id,
                    requireString(json, "category"),
                    requireString(json, "title"),
                    prompt,
                    fixtures,
                    server,
                    checks,
                    json.has("timeoutSeconds") ? json.get("timeoutSeconds").getAsInt() : 0);
        } catch (RuntimeException e) {
            throw new IOException("Task " + id + ": " + e.getMessage(), e);
        }
    }

    private static String requireString(JsonObject json, String key) {
        if (!json.has(key)) {
            throw new IllegalArgumentException("missing \"" + key + "\"");
        }
        return json.get(key).getAsString();
    }

    private static List<String> strings(JsonObject json, String key) {
        if (!json.has(key)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray(key)) {
            out.add(e.getAsString());
        }
        return out;
    }
}
