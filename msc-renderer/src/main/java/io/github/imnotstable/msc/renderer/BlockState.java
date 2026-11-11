package io.github.imnotstable.msc.renderer;

import java.util.HashMap;
import java.util.Map;

/** A parsed vanilla block-state string such as {@code minecraft:oak_stairs[facing=north,half=top]}. */
record BlockState(String namespace, String name, Map<String, String> props) {
    static BlockState parse(String state) {
        String s = state.trim();
        Map<String, String> props = new HashMap<>();
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            int end = s.endsWith("]") ? s.length() - 1 : s.length();
            for (String pair : s.substring(bracket + 1, end).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            s = s.substring(0, bracket);
        }
        int colon = s.indexOf(':');
        return new BlockState(colon < 0 ? "minecraft" : s.substring(0, colon), s.substring(colon + 1), Map.copyOf(props));
    }

    String prop(String key, String fallback) {
        return props.getOrDefault(key, fallback);
    }

    boolean is(String key, String value) {
        return value.equals(props.get(key));
    }
}
