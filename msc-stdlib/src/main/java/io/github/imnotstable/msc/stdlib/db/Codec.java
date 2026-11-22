package io.github.imnotstable.msc.stdlib.db;

import com.google.gson.Gson;
import io.github.imnotstable.msc.stdlib.util.Json;

import java.util.function.Function;

/** Turns a value into text for a {@link Store} and back. */
public interface Codec<V> {
    String encode(V value);

    V decode(String text);

    /** JSON through {@link Json#GSON}: records, classes, lists and maps of them, and {@code java.time} values. */
    static <V> Codec<V> json(Class<V> type) {
        return json(type, Json.GSON);
    }

    /** JSON for a generic type: {@code Codec.json(new TypeToken<List<Kit>>() {}.getType())}. */
    static <V> Codec<V> json(java.lang.reflect.Type type) {
        return of(Json.GSON::toJson, s -> Json.GSON.fromJson(s, type));
    }

    /** JSON through your own Gson, for type adapters. */
    static <V> Codec<V> json(Class<V> type, Gson gson) {
        return of(gson::toJson, s -> gson.fromJson(s, type));
    }

    static Codec<String> string() {
        return of(s -> s, s -> s);
    }

    static <V> Codec<V> of(Function<V, String> encode, Function<String, V> decode) {
        return new Codec<>() {
            @Override
            public String encode(V value) {
                return encode.apply(value);
            }

            @Override
            public V decode(String text) {
                return decode.apply(text);
            }
        };
    }
}
