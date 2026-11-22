package io.github.imnotstable.msc.stdlib.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.stdlib.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Typed settings in a JSON file, for every platform. The file is written with the defaults when missing; settings
 * added to the record later are added to an existing file with their defaults, and what the owner set is kept.
 *
 * <pre>{@code
 * record Settings(int maxPlayers, Duration roundTime, List<String> arenas) implements Config.Validated {
 *     public void validate() { Config.require(maxPlayers > 0, "maxPlayers must be above 0"); }
 * }
 * Settings s = Config.load(dataDir.resolve("settings.json"), Settings.class, () -> new Settings(16, Duration.ofMinutes(5), List.of("a")));
 * }</pre>
 */
public final class Config {
    private Config() {}

    /** A config that checks itself after loading; throw {@link IllegalArgumentException} with what is wrong. */
    public interface Validated {
        void validate();
    }

    public static <T> T load(Path file, Class<T> type, Supplier<T> defaults) {
        try {
            T def = defaults.get();
            JsonObject defJson = Json.GSON.toJsonTree(def).getAsJsonObject();
            JsonObject merged = defJson;
            boolean write = true;
            if (Files.isRegularFile(file)) {
                JsonElement actual = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!actual.isJsonObject()) {
                    throw new IllegalArgumentException(file + ": expected a JSON object");
                }
                merged = Json.merge(defJson, actual.getAsJsonObject());
                write = !merged.equals(actual);
            }
            T value = Json.GSON.fromJson(merged, type);
            if (value instanceof Validated v) {
                try {
                    v.validate();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
                }
            }
            if (write) {
                save(file, value);
            }
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (com.google.gson.JsonParseException e) {
            throw new IllegalArgumentException(file + ": not valid JSON (" + e.getMessage() + ")", e);
        }
    }

    public static void save(Path file, Object value) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Files.writeString(file, Json.PRETTY.toJson(value) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** For {@link Validated#validate}: throws {@link IllegalArgumentException} with {@code message} when false. */
    public static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
