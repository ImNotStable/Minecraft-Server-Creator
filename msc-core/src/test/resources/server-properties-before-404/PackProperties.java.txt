package io.github.imnotstable.msc.core.texture;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * The resource pack lines of a server's {@code server.properties}, so a server restarted outside MSC still sends the
 * pack. Other lines and their order are kept. Nothing is written when the file does not exist (Minestom has none).
 */
public final class PackProperties {
    private static final String URL = "resource-pack";
    private static final String SHA1 = "resource-pack-sha1";
    private static final String ID = "resource-pack-id";
    private static final String REQUIRED = "require-resource-pack";

    private PackProperties() {
    }

    /** The {@code resource-pack} URL, or null when the file or the value is missing. */
    public static String url(Path file) {
        String url = load(file).getProperty(URL, "").strip();
        return url.isEmpty() ? null : url;
    }

    /** Whether {@code require-resource-pack} is true. */
    public static boolean required(Path file) {
        return load(file).getProperty(REQUIRED, "").strip().equals("true");
    }

    private static Properties load(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                // Unreadable: treat as empty.
            }
        }
        return p;
    }

    /** Offer this pack to every player who joins. Returns false when there is no such file. */
    public static boolean write(Path file, String url, String sha1, boolean required) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(URL, url);
        values.put(SHA1, sha1);
        // The same id the msc agent sends the pack with, so a client never stacks the pack twice.
        values.put(ID, UUID.nameUUIDFromBytes(("msc-pack:" + url).getBytes(StandardCharsets.UTF_8)).toString());
        values.put(REQUIRED, String.valueOf(required));
        return set(file, values);
    }

    /** Stop offering a pack. Returns false when there is no such file. */
    public static boolean clear(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(URL, "");
        values.put(SHA1, "");
        values.put(ID, "");
        values.put(REQUIRED, "false");
        return set(file, values);
    }

    private static boolean set(Path file, Map<String, String> values) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Map<String, String> left = new LinkedHashMap<>(values);
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String key = key(line);
            if (key != null && values.containsKey(key)) {
                if (left.remove(key) != null) {
                    out.add(key + "=" + escape(values.get(key)));
                }
            } else {
                out.add(line);
            }
        }
        left.forEach((k, v) -> out.add(k + "=" + escape(v)));
        // "\n" on every OS, so the file and its tests do not depend on where MSC runs.
        Files.writeString(file, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
        return true;
    }

    private static String key(String line) {
        String t = line.stripLeading();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) {
            return null;
        }
        int end = 0;
        while (end < t.length() && "=: \t".indexOf(t.charAt(end)) < 0) {
            end++;
        }
        return t.substring(0, end);
    }

    /** As {@link Properties#store} writes a value: vanilla writes the URL as {@code http\://...}. */
    static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == ':' || c == '=' || c == '#' || c == '!') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
