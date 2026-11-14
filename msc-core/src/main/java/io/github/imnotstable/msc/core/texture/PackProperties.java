package io.github.imnotstable.msc.core.texture;

import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The resource pack lines of a server's {@code server.properties}, so a server restarted outside MSC still sends the
 * pack. Other lines and their order are kept. Nothing is written when the file does not exist (Minestom has none).
 * The file is read and written through msc-process's {@link ServerPropertiesFile} (ADR 0033).
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
        String url = load(file).getOrDefault(URL, "").strip();
        return url.isEmpty() ? null : url;
    }

    /** Whether {@code require-resource-pack} is true. */
    public static boolean required(Path file) {
        return load(file).getOrDefault(REQUIRED, "").strip().equals("true");
    }

    private static Map<String, String> load(Path file) {
        if (Files.isRegularFile(file)) {
            try {
                return ServerPropertiesFile.read(file);
            } catch (IOException e) {
                // Unreadable: treat as empty.
            }
        }
        return Map.of();
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
        ServerPropertiesFile.set(file, values);
        return true;
    }
}
