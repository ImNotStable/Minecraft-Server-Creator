package io.github.imnotstable.msc.bench;

import com.google.gson.JsonPrimitive;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** What a refusal says of a string that is no path on this system; Windows refuses more than Linux, in other words. */
public final class NoPath {
    private NoPath() {}

    /** The reason this system's {@link Path#of} refuses {@code path}, escaped as a refusal shows it, or null. */
    public static String reason(String path) {
        try {
            Path.of(path);
            return null;
        } catch (InvalidPathException e) {
            return Quoted.message(e.getReason());
        }
    }

    /** {@code orElse} where {@code path} is a path here, else the refusal of {@code key} for being none. */
    public static String refusal(String key, String path, String orElse) {
        String reason = reason(path);
        return reason == null ? orElse : key + ": " + Quoted.value(new JsonPrimitive(path), false) + " is not a path: " + reason;
    }
}
