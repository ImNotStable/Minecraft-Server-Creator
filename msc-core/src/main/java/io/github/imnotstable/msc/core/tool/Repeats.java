package io.github.imnotstable.msc.core.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only calls that ask again what a recent identical call already answered (ADR 0119). In the village benchmark, repeated
 * identical calls were the largest waste (up to 22 in one run); when the answer is the same as before, the agent gets a
 * line saying so instead of the whole result once more, which also stays in its context for every later turn.
 */
final class Repeats {
    /** Calls back within which an identical answer is shortened: older ones may have left the agent's context. */
    static final int WINDOW = 20;
    /** Results shorter than this are kept: the line that replaces them would not be much shorter. */
    static final int MIN_CHARS = 200;

    /**
     * Tools that only read: the same answer means nothing changed. A tool that acts (dev_cycle, pack, server_setup) can
     * give the same text after doing its work again, so its answer is always kept.
     */
    static final java.util.Set<String> READS = java.util.Set.of("world_slice", "world_summary", "world_render",
            "world_tour", "world_entities", "world_heightmap", "server_logs", "api_class", "api_search", "texture_view");

    private record Seen(int call, byte[] digest) {
    }

    private final Map<String, Seen> seen = new HashMap<>();
    private int calls;

    /**
     * Counts one call and returns the line that replaces its result, or null to keep it: null unless the same reading
     * tool ({@link #READS}) with the same arguments gave exactly this text and image within the last {@link #WINDOW}
     * calls.
     */
    synchronized String check(String tool, String args, String text, byte[] png) {
        int call = ++calls;
        seen.values().removeIf(s -> call - s.call() > WINDOW);
        if (text == null || !READS.contains(tool) || text.length() + (png == null ? 0 : png.length) < MIN_CHARS) {
            return null;
        }
        String key = tool + '\0' + args;
        byte[] digest = digest(text, png);
        Seen last = seen.put(key, new Seen(call, digest));
        if (last == null || !Arrays.equals(last.digest(), digest)) {
            return null;
        }
        int ago = call - last.call();
        return "same " + (png == null ? "result" : "image and text") + " as your identical " + tool + " call "
                + ago + (ago == 1 ? " call" : " calls") + " ago: nothing it reads changed since, so it is not repeated "
                + "here. Act on that answer; asking again returns the same.";
    }

    private static byte[] digest(String text, byte[] png) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(text.getBytes(StandardCharsets.UTF_8));
            if (png != null) {
                md.update((byte) 0);
                md.update(png);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
