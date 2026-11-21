package io.github.imnotstable.msc.bench.check;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Passes when a zip (such as a resource pack) has the listed entries, its PNG entries meet their rules
 * and its text entries match their patterns. With {@code packFormat} (a number, or null for no rule) its pack.mcmeta
 * must also load on that resource pack format: {@code pack_format} equal to it, or a {@code min_format} to
 * {@code max_format} range around it.
 */
record ZipCheck(String name, String path, List<String> entries, List<PngEntry> pngEntries, List<TextEntry> textEntries,
        String packFormat) implements Check {

    record PngEntry(String entry, PngRules rules) {
    }

    record TextEntry(String entry, Pattern contains) {
    }

    ZipCheck {
        entries = List.copyOf(entries);
        pngEntries = List.copyOf(pngEntries);
        textEntries = List.copyOf(textEntries);
    }

    @Override
    public CheckResult run(CheckContext context) {
        Path file = context.workspace().resolve(path);
        if (!Files.isRegularFile(file)) {
            return CheckResult.fail(name, path + " not found");
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> problems = new ArrayList<>();
            for (String entry : entries) {
                if (zip.getEntry(entry) == null) {
                    problems.add("missing " + entry);
                }
            }
            for (PngEntry png : pngEntries) {
                ZipEntry entry = zip.getEntry(png.entry());
                if (entry == null) {
                    problems.add("missing " + png.entry());
                    continue;
                }
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                for (String p : png.rules().problems(bytes)) {
                    problems.add(png.entry() + ": " + p);
                }
            }
            for (TextEntry text : textEntries) {
                ZipEntry entry = zip.getEntry(text.entry());
                if (entry == null) {
                    problems.add("missing " + text.entry());
                } else if (!text.contains().matcher(
                        Text.decode(zip.getInputStream(entry).readAllBytes())).find()) {
                    problems.add(text.entry() + " has no match for /" + text.contains().pattern() + "/");
                }
            }
            if (packFormat != null) {
                ZipEntry meta = zip.getEntry("pack.mcmeta");
                String problem = meta == null ? "missing pack.mcmeta"
                        : formatProblem(Text.decode(zip.getInputStream(meta).readAllBytes()), packFormat);
                if (problem != null) {
                    problems.add(problem);
                }
            }
            return problems.isEmpty()
                    ? CheckResult.pass(name, path + " has every required entry")
                    : CheckResult.fail(name, String.join("; ", problems));
        } catch (IOException e) {
            return CheckResult.fail(name, path + " is not a readable zip: " + e.getMessage());
        }
    }

    /** Why pack.mcmeta {@code text} does not load on resource pack format {@code wanted}, or null when it does. */
    static String formatProblem(String text, String wanted) {
        int want;
        try {
            want = Integer.parseInt(wanted);
        } catch (NumberFormatException e) {
            return "no resource pack format is known for this run's Minecraft version (" + wanted + ")";
        }
        JsonObject pack;
        try {
            pack = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("pack");
        } catch (RuntimeException e) {
            pack = null;
        }
        if (pack == null) {
            return "pack.mcmeta has no \"pack\" object";
        }
        if (pack.has("min_format")) {
            Integer min = major(pack.get("min_format"));
            Integer max = major(pack.get("max_format"));
            if (min == null || max == null) {
                return "pack.mcmeta min_format needs a max_format, each a number or [major, minor]";
            }
            return min <= want && want <= max ? null
                    : "pack.mcmeta formats " + min + " to " + max + " leave out format " + want;
        }
        Integer format = major(pack.get("pack_format"));
        return format != null && format == want ? null
                : "pack.mcmeta pack_format is " + pack.get("pack_format") + ", the game needs " + want;
    }

    // shortcut: compares major formats only ([97, 0] covers 97.1), enough to tell a pack made for another version.
    private static Integer major(JsonElement e) {
        if (e != null && e.isJsonArray() && !e.getAsJsonArray().isEmpty()) {
            e = e.getAsJsonArray().get(0);
        }
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : null;
    }
}
