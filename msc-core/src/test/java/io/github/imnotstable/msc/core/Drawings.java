package io.github.imnotstable.msc.core;

import java.util.List;
import java.util.stream.IntStream;

/** A 16x16 four-tone drawing for texture calls in tests: msc has no stock textures to generate (ADR 0106). */
final class Drawings {
    private Drawings() {
    }

    /** {@code color} as the base, with an outline, a shadow and a highlight. */
    static List<String> palette(String color) {
        return List.of("r=" + color, "s=#2a0a10", "t=#5a1a20", "u=#f0d0c0");
    }

    static List<String> rows() {
        String row = "rrsstrrsutrrsstu";
        return IntStream.range(0, 16).mapToObj(y -> row.substring(y) + row.substring(0, y)).toList();
    }
}
