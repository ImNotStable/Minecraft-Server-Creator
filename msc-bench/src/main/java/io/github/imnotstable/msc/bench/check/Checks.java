package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.UserJson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Builds checks from their JSON form in {@code task.json}. See the msc-bench README for the fields. */
public final class Checks {

    private Checks() {
    }

    /**
     * Parses a check; {@code taskDir} resolves folders a check reads from the task, such as hidden tests. A refused
     * number names the check's file and key path, such as {@code task.json checks[2].timeoutSeconds} ({@link UserJson}).
     */
    public static Check parse(UserJson json, Path taskDir) {
        String type = string(json, "type", null);
        if (type == null) {
            throw new IllegalArgumentException("check without \"type\": " + json);
        }
        String name = string(json, "name", null);
        return switch (type) {
            case "file" -> new FileCheck(
                    orDefault(name, "file " + string(json, "path", "")),
                    require(json, "path"),
                    pattern(json, "contains"));
            case "command" -> new CommandCheck(
                    orDefault(name, "command"),
                    strings(json, "run"),
                    json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 600),
                    json.wholeNumber("expectExit", JsonNumbers.EXIT_CODE, 0),
                    pattern(json, "outputMatches"),
                    taskFolder(json, "addFiles", taskDir));
            case "png" -> new PngCheck(
                    orDefault(name, "png " + string(json, "path", "")),
                    require(json, "path"),
                    PngRules.parse(json));
            case "zip" -> new ZipCheck(
                    orDefault(name, "zip " + string(json, "path", "")),
                    require(json, "path"),
                    json.has("entries") ? strings(json, "entries") : List.of(),
                    zipPngs(json),
                    zipTexts(json));
            case "rcon" -> new RconCheck(
                    orDefault(name, "rcon " + string(json, "command", "")),
                    require(json, "command"),
                    Pattern.compile(require(json, "expect")));
            case "blocks" -> BlocksCheck.parse(orDefault(name, "blocks"), json);
            case "player" -> PlayerCheck.parse(orDefault(name, "player"), json);
            default -> throw new IllegalArgumentException("unknown check type \"" + type + "\"");
        };
    }

    /** A folder of the task named by {@code key}, which must exist, or null when the key is absent. */
    private static Path taskFolder(UserJson json, String key, Path taskDir) {
        String folder = string(json, key, null);
        if (folder == null) {
            return null;
        }
        if (taskDir == null) {
            throw new IllegalArgumentException("\"" + key + "\" needs the task's folder");
        }
        Path dir = taskDir.resolve(folder).normalize();
        if (!dir.startsWith(taskDir.normalize()) || !java.nio.file.Files.isDirectory(dir)) {
            throw new IllegalArgumentException("\"" + key + "\": no folder " + folder + " in the task");
        }
        return dir;
    }

    private static List<ZipCheck.PngEntry> zipPngs(UserJson json) {
        List<ZipCheck.PngEntry> out = new ArrayList<>();
        if (json.has("pngEntries")) {
            for (UserJson o : json.objects("pngEntries")) {
                out.add(new ZipCheck.PngEntry(require(o, "entry"), PngRules.parse(o)));
            }
        }
        return out;
    }

    private static List<ZipCheck.TextEntry> zipTexts(UserJson json) {
        List<ZipCheck.TextEntry> out = new ArrayList<>();
        if (json.has("textEntries")) {
            for (UserJson o : json.objects("textEntries")) {
                out.add(new ZipCheck.TextEntry(require(o, "entry"), Pattern.compile(require(o, "contains"), Pattern.MULTILINE)));
            }
        }
        return out;
    }

    static String require(UserJson json, String key) {
        String value = string(json, key, null);
        if (value == null) {
            throw new IllegalArgumentException("check is missing \"" + key + "\": " + json);
        }
        return value;
    }

    static String string(UserJson json, String key, String fallback) {
        return json.string(key, fallback);
    }

    static List<String> strings(UserJson json, String key) {
        if (!json.has(key)) {
            throw new IllegalArgumentException("check is missing \"" + key + "\": " + json);
        }
        return json.strings(key);
    }

    private static Pattern pattern(UserJson json, String key) {
        String regex = string(json, key, null);
        return regex == null ? null : Pattern.compile(regex, Pattern.MULTILINE);
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
