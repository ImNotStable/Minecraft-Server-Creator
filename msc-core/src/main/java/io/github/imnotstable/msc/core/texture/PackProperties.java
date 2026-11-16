package io.github.imnotstable.msc.core.texture;

import io.github.imnotstable.msc.core.server.Platform;
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
 * pack. Other lines and their order are kept. Every call names the platform: for one that does not read its pack from
 * the file ({@link Platform#readsPackProperties()}: Minestom, Velocity) the file offers no pack and nothing is written,
 * even when the folder has one (#718); nor is anything written when the file does not exist. The file is read and
 * written through msc-process's {@link ServerPropertiesFile} (ADR 0033).
 */
public final class PackProperties {
    private static final String URL = "resource-pack";
    private static final String SHA1 = "resource-pack-sha1";
    private static final String ID = "resource-pack-id";
    private static final String REQUIRED = "require-resource-pack";

    private PackProperties() {
    }

    /** The {@code resource-pack} URL {@code platform} reads, or null when it reads none or the value is missing. */
    public static String url(Platform platform, Path file) {
        String url = load(platform, file).getOrDefault(URL, "").strip();
        return url.isEmpty() ? null : url;
    }

    /** Whether {@code require-resource-pack} is true. */
    public static boolean required(Platform platform, Path file) {
        return load(platform, file).getOrDefault(REQUIRED, "").strip().equals("true");
    }

    private static Map<String, String> load(Platform platform, Path file) {
        if (platform.readsPackProperties() && Files.isRegularFile(file)) {
            try {
                return ServerPropertiesFile.read(file);
            } catch (IOException e) {
                // Unreadable: treat as empty.
            }
        }
        return Map.of();
    }

    /** Offer this pack to every player who joins. Returns false when {@code platform} reads none or there is no file. */
    public static boolean write(Platform platform, Path file, String url, String sha1, boolean required)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(URL, url);
        values.put(SHA1, sha1);
        // The same id the msc agent sends the pack with, so a client never stacks the pack twice.
        values.put(ID, UUID.nameUUIDFromBytes(("msc-pack:" + url).getBytes(StandardCharsets.UTF_8)).toString());
        values.put(REQUIRED, String.valueOf(required));
        return set(platform, file, values);
    }

    /** Stop offering a pack. Returns false when {@code platform} reads none or there is no file. */
    public static boolean clear(Platform platform, Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(URL, "");
        values.put(SHA1, "");
        values.put(ID, "");
        values.put(REQUIRED, "false");
        return set(platform, file, values);
    }

    private static boolean set(Platform platform, Path file, Map<String, String> values) throws IOException {
        if (!platform.readsPackProperties() || !Files.isRegularFile(file)) {
            return false;
        }
        ServerPropertiesFile.set(file, values);
        return true;
    }
}
