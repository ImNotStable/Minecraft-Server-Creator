package io.github.imnotstable.msc.core.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * {@code .msc/deployed.properties}, read and rewritten one entry at a time (#418). Every readable entry counts, also
 * when another line of the file is not readable as a properties line (a hand-edited Windows path written with single
 * backslashes, where a backslash before a folder name starting with u begins a broken unicode escape): that line is
 * skipped and named in {@link #note}. A rewrite changes only the lines of the one project it records; an unreadable
 * line stays in the file word for word, behind {@link #KEPT}, so it is not lost and no longer trips a properties
 * reader.
 *
 * <p>The file is ISO-8859-1, as {@link Properties#load(java.io.InputStream)} reads it, so every byte of a line that is
 * kept goes back unchanged.
 */
final class DeployRecords {
    static final String HEADER = "#jar dev_cycle last deployed per project";
    /** What an unreadable line is kept behind when the file is rewritten: a comment, so readers skip it. */
    static final String KEPT = "# unreadable, kept as written: ";

    /**
     * One logical line (a value may continue over physical lines): its text, and its entry if it has one, or whether
     * it could not be read.
     */
    private record Line(String text, String key, String value, boolean unreadable) {
    }

    private final List<Line> lines;
    private final List<Integer> unreadable;

    private DeployRecords(List<Line> lines, List<Integer> unreadable) {
        this.lines = lines;
        this.unreadable = unreadable;
    }

    /** The records in {@code file}; none when it does not exist. */
    static DeployRecords read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return parse("");
        }
        return parse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
    }

    static DeployRecords parse(String text) {
        List<Line> lines = new ArrayList<>();
        List<Integer> unreadable = new ArrayList<>();
        String[] physical = text.split("\r\n|\r|\n", -1);
        int count = text.isEmpty() ? 0 : text.endsWith("\n") || text.endsWith("\r") ? physical.length - 1 : physical.length;
        for (int i = 0; i < count; i++) {
            int first = i;
            StringBuilder logical = new StringBuilder(physical[i]);
            while (continues(physical[i]) && i + 1 < count) {
                logical.append('\n').append(physical[++i]);
            }
            String raw = logical.toString();
            String stripped = raw.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
                lines.add(new Line(raw, null, null, false));
                continue;
            }
            Properties one = new Properties();
            try {
                one.load(new StringReader(raw));
            } catch (IllegalArgumentException | IOException malformed) {
                unreadable.add(first + 1);
                lines.add(new Line(raw, null, null, true));
                continue;
            }
            String key = one.stringPropertyNames().stream().findFirst().orElse(null);
            lines.add(new Line(raw, key, key == null ? null : one.getProperty(key), false));
        }
        return new DeployRecords(lines, unreadable);
    }

    /** A line that is not a comment and ends in an odd number of backslashes goes on in the next one. */
    private static boolean continues(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("#") || stripped.startsWith("!")) {
            return false;
        }
        int slashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    /** The jar recorded for {@code key}, the last entry for it as {@link Properties} would read it; or null. */
    String get(String key) {
        String value = null;
        for (Line line : lines) {
            if (key.equals(line.key())) {
                value = line.value();
            }
        }
        return value;
    }

    /** The 1-based numbers of the lines that could not be read. */
    List<Integer> unreadable() {
        return List.copyOf(unreadable);
    }

    /** A note for the tool result when a line was skipped; null when every line was readable. */
    String note(Path file) {
        if (unreadable.isEmpty()) {
            return null;
        }
        return "deploy record: " + (unreadable.size() == 1 ? "line " : "lines ")
                + String.join(", ", unreadable.stream().map(String::valueOf).toList()) + " of " + file
                + " could not be read (a backslash in a hand-edited path must be written \\\\) and "
                + (unreadable.size() == 1 ? "was" : "were") + " skipped; the other records were used, and the file "
                + "keeps the line as a comment starting '" + KEPT.strip() + "'";
    }

    /**
     * The file's text with {@code key} recorded as {@code value}: its earlier entries go, every other line (other
     * projects, comments) stays as it was, in its place, and an unreadable line stays behind {@link #KEPT}.
     */
    String with(String key, String value) {
        StringBuilder out = new StringBuilder();
        if (lines.isEmpty()) {
            out.append(HEADER).append('\n');
        }
        for (Line line : lines) {
            if (line.unreadable()) {
                line.text().lines().forEach(l -> out.append(KEPT).append(l).append('\n'));
            } else if (!key.equals(line.key())) {
                out.append(line.text()).append('\n');
            }
        }
        return out.append(entry(key, value)).append('\n').toString();
    }

    /** {@code key=value} escaped as {@link Properties#store(java.io.OutputStream, String)} writes it. */
    private static String entry(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            p.store(bytes, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).lines()
                .filter(l -> !l.startsWith("#")).findFirst().orElseThrow();
    }
}
