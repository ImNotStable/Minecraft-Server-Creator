package io.github.imnotstable.msc.bench.check;

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
 * and its text entries match their patterns.
 */
record ZipCheck(String name, String path, List<String> entries, List<PngEntry> pngEntries, List<TextEntry> textEntries)
        implements Check {

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
            return problems.isEmpty()
                    ? CheckResult.pass(name, path + " has every required entry")
                    : CheckResult.fail(name, String.join("; ", problems));
        } catch (IOException e) {
            return CheckResult.fail(name, path + " is not a readable zip: " + e.getMessage());
        }
    }
}
