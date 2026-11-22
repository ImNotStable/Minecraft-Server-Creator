package io.github.imnotstable.msc.stdlib.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * The Gson every part of msc-stdlib uses: records, lists, maps, and the {@code java.time} types Gson cannot read on its
 * own ({@link Instant}, {@link Duration}, {@link LocalDate}, {@link LocalDateTime}) as ISO text.
 */
public final class Json {
    /** Compact JSON. */
    public static final Gson GSON = builder().create();
    /** Indented JSON, for files people edit. */
    public static final Gson PRETTY = builder().setPrettyPrinting().create();

    private Json() {}

    /** Start from this to add your own type adapters. */
    public static GsonBuilder builder() {
        return new GsonBuilder().disableHtmlEscaping()
                .registerTypeAdapter(Instant.class, text(Instant::toString, Instant::parse).nullSafe())
                .registerTypeAdapter(Duration.class, text(Duration::toString, Duration::parse).nullSafe())
                .registerTypeAdapter(LocalDate.class, text(LocalDate::toString, LocalDate::parse).nullSafe())
                .registerTypeAdapter(LocalDateTime.class, text(LocalDateTime::toString, LocalDateTime::parse).nullSafe());
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T read(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    /**
     * {@code defaults} with every key {@code actual} also has replaced by its value, objects merged key by key. Keys
     * only {@code actual} has are kept too. Used by {@link io.github.imnotstable.msc.stdlib.config.Config} to add new
     * settings to an old file.
     */
    public static JsonObject merge(JsonObject defaults, JsonObject actual) {
        JsonObject out = defaults.deepCopy();
        for (var e : actual.entrySet()) {
            JsonElement mine = out.get(e.getKey());
            if (mine != null && mine.isJsonObject() && e.getValue().isJsonObject()) {
                out.add(e.getKey(), merge(mine.getAsJsonObject(), e.getValue().getAsJsonObject()));
            } else {
                out.add(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static <T> TypeAdapter<T> text(Function<T, String> write, Function<String, T> read) {
        return new TypeAdapter<>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                out.value(write.apply(value));
            }

            @Override
            public T read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                return read.apply(in.nextString());
            }
        };
    }
}
