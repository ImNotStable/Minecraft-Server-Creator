package io.github.imnotstable.msc.bench.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.Quoted;
import io.github.imnotstable.msc.bench.StrictJson;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.Checks;
import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads tasks from a folder laid out as {@code <tasksDir>/<id>/task.json} plus {@code prompt.md}
 * and an optional {@code fixture/} folder (copied into the workspace). Other folders, such as hidden tests a
 * check adds after the agent is done, stay out of the workspace. Folders starting with {@code _} hold shared fixtures and
 * are not tasks.
 */
public final class TaskLoader {

    /**
     * The keys a task.json may have. Every object in it is made with its own keys ({@link UserJson.Keys}), so a
     * misspelt key at any depth is refused instead of left out silently (#502): a reset that never happens, a time
     * limit never applied, a {@code "contain"} whose file check would pass for any file.
     */
    public static final UserJson.Keys KEYS = UserJson.Keys.of("category", "title", "platform", "scaffold",
            "fixtures", "server", "checks", "resetBeforeChecks", "timeoutSeconds");

    /** The keys of {@code scaffold}. */
    public static final UserJson.Keys SCAFFOLD = UserJson.Keys.of("template", "name", "package");

    /** The keys of {@code server}. */
    public static final UserJson.Keys SERVER = UserJson.Keys.of("startBefore", "setup");

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
        String text = Text.readOwn(taskDir.resolve("task.json"));
        JsonObject tree;
        try {
            JsonElement parsed = StrictJson.parse(text, "task.json ");
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("task.json is not a JSON object");
            }
            tree = parsed.getAsJsonObject();
        } catch (IllegalArgumentException e) {
            throw new IOException("Task " + id + ": " + e.getMessage(), e);
        }
        try {
            UserJson json = UserJson.of(tree, "task.json ", KEYS);
            Path promptFile = taskDir.resolve("prompt.md");
            if (!Files.isRegularFile(promptFile)) {
                throw new IllegalArgumentException("prompt.md is missing");
            }
            String prompt = Text.readOwn(promptFile).strip();

            List<Path> fixtures = new ArrayList<>();
            List<String> sharedFixtures = json.paths("fixtures");
            for (int i = 0; i < sharedFixtures.size(); i++) {
                String shared = sharedFixtures.get(i);
                unfilled(json.key("fixtures") + "[" + i + "]", shared);
                Path fixture = taskDir.getParent().resolve(shared).normalize();
                if (!Files.isDirectory(fixture)) {
                    throw new IllegalArgumentException(json.key("fixtures") + ": no folder " + shared + " next to the task "
                            + "folders");
                }
                fixtures.add(fixture);
            }
            if (Files.isDirectory(taskDir.resolve("fixture"))) {
                fixtures.add(taskDir.resolve("fixture"));
            }

            String platform = json.exactString("platform", TaskDef.PAPER);
            if (!platform.equals(TaskDef.PAPER) && !platform.equals(TaskDef.MINESTOM)) {
                throw new IllegalArgumentException(json.key("platform") + ": " + Quoted.text(platform)
                        + " is no platform; use paper or minestom");
            }
            Scaffold scaffold = null;
            UserJson s = json.exactObject("scaffold", SCAFFOLD);
            if (s != null) {
                String template = requireString(s, "template");
                if (!Templates.ids().contains(template)) {
                    throw new IllegalArgumentException(s.key("template") + ": " + Quoted.text(template)
                            + " is no template; use one of " + String.join(", ", Templates.ids()));
                }
                scaffold = new Scaffold(template, requireString(s, "name"), requireString(s, "package"));
            }

            ServerSpec server = ServerSpec.NONE;
            UserJson spec = json.exactObject("server", SERVER);
            if (spec != null) {
                server = new ServerSpec(spec.exactBool("startBefore", false), spec.exactStrings("setup"));
                // The runner sends the setup commands only to the server it starts before the agent (#564).
                if (!server.startBefore() && !server.setup().isEmpty()) {
                    throw new IllegalArgumentException(spec.key("setup") + ": runs only before the agent, so it needs "
                            + "\"startBefore\": true");
                }
                // A Minestom task runs no Paper server, so the runner would start one the agent never touched (#570).
                if (server.startBefore() && platform.equals(TaskDef.MINESTOM)) {
                    throw new IllegalArgumentException(spec.key("startBefore") + ": starts the Paper server, and a "
                            + "minestom task runs none");
                }
            }

            List<Check> checks = new ArrayList<>();
            for (UserJson check : json.exactObjects("checks", Checks.KEYS)) {
                Check parsed = Checks.parse(check, taskDir);
                if (parsed.needsServer() && platform.equals(TaskDef.MINESTOM)) {
                    throw new IllegalArgumentException(check.key("type") + ": " + Quoted.text(check.exactString(
                            "type", "")) + " checks the Paper server, and a minestom task runs none");
                }
                checks.add(parsed);
            }
            if (checks.isEmpty()) {
                throw new IllegalArgumentException("a task needs at least one check");
            }

            String category = requireString(json, "category");
            String title = requireString(json, "title");
            UserJson resetJson = json.exactObject("resetBeforeChecks", Reset.KEYS);
            Reset reset = Reset.parse(resetJson);
            // #578: the runner fills placeholders in server.setup and in every check key; these keys are read as
            // written, so a placeholder in one would stay there and quietly name nothing.
            unfilled(json.key("category"), category);
            unfilled(json.key("title"), title);
            if (s != null) {
                unfilled(s.key("name"), scaffold.name());
                unfilled(s.key("package"), scaffold.packageName());
            }
            for (int i = 0; i < reset.delete().size(); i++) {
                unfilled(resetJson.key("delete") + "[" + i + "]", reset.delete().get(i));
            }

            return new TaskDef(
                    id,
                    category,
                    title,
                    platform,
                    prompt,
                    scaffold,
                    fixtures,
                    server,
                    checks,
                    reset,
                    json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 0));
        } catch (RuntimeException e) {
            throw new IOException("Task " + id + ": " + e.getMessage(), e);
        }
    }

    /** The placeholders the runner fills, such as {@code {scaffoldVersion}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9._-]+)}");

    /**
     * Refuses a runner placeholder in {@code value} of a key the runner does not fill (#578), naming the key: the
     * placeholder would stay as written and the task would quietly not do what it says.
     */
    static void unfilled(String key, String value) {
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            if (BenchConfig.RUNNER_PLACEHOLDERS.contains(m.group(1))) {
                throw new IllegalArgumentException(key + ": " + Quoted.text(value) + " holds " + m.group()
                        + ", and placeholders are filled only in server.setup and in checks");
            }
        }
    }

    private static String requireString(UserJson json, String key) {
        String value = json.exactString(key, null);
        if (value == null) {
            throw new IllegalArgumentException(json.key(key) + ": is missing");
        }
        return value;
    }
}
