package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.Quoted;
import io.github.imnotstable.msc.bench.UserJson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds checks from their JSON form in {@code task.json}. See the msc-bench README for the fields. */
public final class Checks {

    /** The keys of each check type (#502); a check holds its type's keys and no other. */
    public static final UserJson.Keys KEYS = UserJson.Keys.byType("type", types());

    private static final UserJson.Keys TEXT_ENTRY = UserJson.Keys.of("entry", "contains");
    private static final UserJson.Keys PNG_ENTRY = UserJson.Keys.of(with(PngRules.KEYS, "entry"));

    private Checks() {
    }

    private static Map<String, UserJson.Keys> types() {
        Map<String, UserJson.Keys> types = new LinkedHashMap<>();
        types.put("file", UserJson.Keys.of("type", "name", "path", "contains"));
        types.put("command", UserJson.Keys.of("type", "name", "run", "timeoutSeconds", "expectExit", "outputMatches",
                "addFiles"));
        types.put("png", UserJson.Keys.of(with(PngRules.KEYS, "type", "name", "path")));
        types.put("zip", UserJson.Keys.of("type", "name", "path", "entries", "pngEntries", "textEntries"));
        types.put("rcon", UserJson.Keys.of("type", "name", "command", "expect"));
        types.put("blocks", UserJson.Keys.of("type", "name", "blocks", "regions"));
        types.put("player", UserJson.Keys.of("type", "name", "jar", "command", "port", "player", "script", "console",
                "ready", "expect", "timeoutSeconds"));
        return types;
    }

    /** {@code first} followed by {@code keys}. */
    private static List<String> with(List<String> keys, String... first) {
        List<String> out = new ArrayList<>(List.of(first));
        out.addAll(keys);
        return out;
    }

    /**
     * Parses a check made with {@link #KEYS}; {@code taskDir} resolves folders a check reads from the task, such as
     * hidden tests. A refused number names the check's file and key path, such as
     * {@code task.json checks[2].timeoutSeconds} ({@link UserJson}).
     */
    public static Check parse(UserJson json, Path taskDir) {
        return new Written(json, taskDir, read(json, taskDir));
    }

    /**
     * A check as task.json writes it. Each run reads it again from its JSON with the attempt's placeholders filled
     * ({@link UserJson#filled}), so every string key of every check type is filled through this one path (#578), and
     * no check fills a key of its own. Read once without them when the task loads, so a check that can never work is
     * refused then.
     */
    private record Written(UserJson json, Path taskDir, Check unfilled) implements Check {

        @Override
        public String name() {
            return unfilled.name();
        }

        @Override
        public boolean needsServer() {
            return unfilled.needsServer();
        }

        @Override
        public boolean startsServer() {
            return unfilled.startsServer();
        }

        @Override
        public Path addFiles() {
            return unfilled.addFiles();
        }

        @Override
        public CheckResult run(CheckContext context) {
            Check filled;
            try {
                filled = read(json.filled(context.placeholders()), taskDir);
            } catch (IllegalArgumentException e) {
                // A value made the check unreadable, such as a port placeholder filled with no number.
                return CheckResult.fail(unfilled.name(), e.getMessage());
            }
            return filled.run(context);
        }
    }

    /** The check {@code json} describes, read as it is (filled or not). */
    static Check read(UserJson json, Path taskDir) {
        // The keys refuse a check without a type before it is read.
        String type = string(json, "type", "");
        String name = string(json, "name", null);
        return switch (type) {
            case "file" -> new FileCheck(
                    orDefault(name, "file " + string(json, "path", "")),
                    requirePath(json, "path"),
                    json.pattern("contains", Pattern.MULTILINE));
            case "command" -> new CommandCheck(
                    orDefault(name, "command"),
                    strings(json, "run"),
                    json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 600),
                    json.wholeNumber("expectExit", JsonNumbers.EXIT_CODE, 0),
                    json.pattern("outputMatches", Pattern.MULTILINE),
                    taskFolder(json, "addFiles", taskDir));
            case "png" -> new PngCheck(
                    orDefault(name, "png " + string(json, "path", "")),
                    requirePath(json, "path"),
                    PngRules.parse(json));
            case "zip" -> new ZipCheck(
                    orDefault(name, "zip " + string(json, "path", "")),
                    requirePath(json, "path"),
                    json.exactStrings("entries"),
                    zipPngs(json),
                    zipTexts(json));
            case "rcon" -> new RconCheck(
                    orDefault(name, "rcon " + string(json, "command", "")),
                    require(json, "command"),
                    requirePattern(json, "expect", 0));
            case "blocks" -> BlocksCheck.parse(orDefault(name, "blocks"), json);
            case "player" -> PlayerCheck.parse(orDefault(name, "player"), json);
            default -> throw new IllegalArgumentException("unknown check type " + Quoted.text(type));
        };
    }

    /** A folder of the task named by {@code key}, which must exist, or null when the key is absent. */
    private static Path taskFolder(UserJson json, String key, Path taskDir) {
        String folder = json.path(key, null);
        if (folder == null) {
            return null;
        }
        if (taskDir == null) {
            throw new IllegalArgumentException(json.key(key) + ": needs the task's folder");
        }
        Path dir = taskDir.resolve(folder).normalize();
        if (!dir.startsWith(taskDir.normalize()) || !java.nio.file.Files.isDirectory(dir)) {
            throw new IllegalArgumentException(json.key(key) + ": no folder " + Quoted.text(folder) + " in the task");
        }
        return dir;
    }

    private static List<ZipCheck.PngEntry> zipPngs(UserJson json) {
        List<ZipCheck.PngEntry> out = new ArrayList<>();
        for (UserJson o : json.exactObjects("pngEntries", PNG_ENTRY)) {
            out.add(new ZipCheck.PngEntry(require(o, "entry"), PngRules.parse(o)));
        }
        return out;
    }

    private static List<ZipCheck.TextEntry> zipTexts(UserJson json) {
        List<ZipCheck.TextEntry> out = new ArrayList<>();
        for (UserJson o : json.exactObjects("textEntries", TEXT_ENTRY)) {
            out.add(new ZipCheck.TextEntry(require(o, "entry"), requirePattern(o, "contains", Pattern.MULTILINE)));
        }
        return out;
    }

    static String require(UserJson json, String key) {
        String value = string(json, key, null);
        if (value == null) {
            throw new IllegalArgumentException(json.key(key) + ": is missing");
        }
        return value;
    }

    /** The path {@code key}, which must be there and be a path on this system. */
    static String requirePath(UserJson json, String key) {
        require(json, key);
        return json.path(key, null);
    }

    /** The regex {@code key}, which must be there, compiled through {@link UserJson#pattern} (#508). */
    private static Pattern requirePattern(UserJson json, String key, int flags) {
        require(json, key);
        return json.pattern(key, flags);
    }

    static String string(UserJson json, String key, String fallback) {
        return json.exactString(key, fallback);
    }

    static List<String> strings(UserJson json, String key) {
        List<String> value = json.exactStrings(key, null);
        if (value == null) {
            throw new IllegalArgumentException(json.key(key) + ": is missing");
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
