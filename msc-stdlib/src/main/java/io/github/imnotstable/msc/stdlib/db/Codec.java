package io.github.imnotstable.msc.stdlib.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.function.Function;

/** Turns a value into text for a {@link Store} and back. */
public interface Codec<V> {
    String encode(V value);

    V decode(String text);

    /** JSON through Gson: records, classes, lists and maps of them. */
    static <V> Codec<V> json(Class<V> type) {
        return json(type, new GsonBuilder().disableHtmlEscaping().create());
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
