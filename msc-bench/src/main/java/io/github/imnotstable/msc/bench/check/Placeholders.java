package io.github.imnotstable.msc.bench.check;

import java.util.List;
import java.util.Map;

/** Fills {@code {name}} placeholders. Unknown names are left as they are. */
public final class Placeholders {

    private Placeholders() {
    }

    public static String fill(String text, Map<String, String> values) {
        String out = text;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    public static List<String> fill(List<String> texts, Map<String, String> values) {
        return texts.stream().map(t -> fill(t, values)).toList();
    }
}
