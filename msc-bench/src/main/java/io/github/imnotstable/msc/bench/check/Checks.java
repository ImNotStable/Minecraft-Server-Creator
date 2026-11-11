package io.github.imnotstable.msc.bench.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Builds checks from their JSON form in {@code task.json}. See the msc-bench README for the fields. */
public final class Checks {

    private Checks() {
    }

    public static Check parse(JsonObject json) {
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
                    json.has("timeoutSeconds") ? json.get("timeoutSeconds").getAsInt() : 600,
                    json.has("expectExit") ? json.get("expectExit").getAsInt() : 0,
                    pattern(json, "outputMatches"));
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
            default -> throw new IllegalArgumentException("unknown check type \"" + type + "\"");
        };
    }

    private static List<ZipCheck.PngEntry> zipPngs(JsonObject json) {
        List<ZipCheck.PngEntry> out = new ArrayList<>();
        if (json.has("pngEntries")) {
            for (JsonElement e : json.getAsJsonArray("pngEntries")) {
                JsonObject o = e.getAsJsonObject();
                out.add(new ZipCheck.PngEntry(require(o, "entry"), PngRules.parse(o)));
            }
        }
        return out;
    }

    private static List<ZipCheck.TextEntry> zipTexts(JsonObject json) {
        List<ZipCheck.TextEntry> out = new ArrayList<>();
        if (json.has("textEntries")) {
            for (JsonElement e : json.getAsJsonArray("textEntries")) {
                JsonObject o = e.getAsJsonObject();
                out.add(new ZipCheck.TextEntry(require(o, "entry"), Pattern.compile(require(o, "contains"), Pattern.MULTILINE)));
            }
        }
        return out;
    }

    static String require(JsonObject json, String key) {
        String value = string(json, key, null);
        if (value == null) {
            throw new IllegalArgumentException("check is missing \"" + key + "\": " + json);
        }
        return value;
    }

    static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    static List<String> strings(JsonObject json, String key) {
        if (!json.has(key)) {
            throw new IllegalArgumentException("check is missing \"" + key + "\": " + json);
        }
        JsonArray array = json.getAsJsonArray(key);
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static Pattern pattern(JsonObject json, String key) {
        String regex = string(json, key, null);
        return regex == null ? null : Pattern.compile(regex, Pattern.MULTILINE);
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
