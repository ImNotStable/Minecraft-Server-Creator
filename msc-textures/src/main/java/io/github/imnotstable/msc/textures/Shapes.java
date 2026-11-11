package io.github.imnotstable.msc.textures;

import java.util.List;
import java.util.Map;

/**
 * Shaded 16x16 item silhouettes, coloured from a ramp. Light comes from the top left as in vanilla
 * items. The templates use {@code o} for the outline, {@code 1} deep shadow, {@code 2} shadow,
 * {@code 3} base colour, {@code 4} light, {@code 5} glint, and {@code .} for transparent.
 */
public final class Shapes {
    private static final Map<String, List<String>> TEMPLATES = Map.of(
            "gem", List.of(
                    "................",
                    "................",
                    "....oooooooo....",
                    "...o45433332o...",
                    "..o4543333222o..",
                    ".o444333332221o.",
                    ".o333333322221o.",
                    "..o3333322221o..",
                    "...o33332221o...",
                    "....o333221o....",
                    ".....o3221o.....",
                    "......o21o......",
                    ".......oo.......",
                    "................",
                    "................",
                    "................"),
            "ingot", List.of(
                    "................",
                    "................",
                    "................",
                    "................",
                    "................",
                    "....oooooooo....",
                    "...o45544444o...",
                    "..o4333333332o..",
                    ".o433333333332o.",
                    ".o322222222221o.",
                    ".o211111111111o.",
                    ".oooooooooooooo.",
                    "................",
                    "................",
                    "................",
                    "................"),
            "orb", List.of(
                    "................",
                    ".....oooooo.....",
                    "...oo333333oo...",
                    "..o3345433332o..",
                    "..o3455433322o..",
                    ".o334443333221o.",
                    ".o333333333221o.",
                    ".o333333332221o.",
                    ".o333333322221o.",
                    ".o233333222211o.",
                    "..o2222222211o..",
                    "..o1222222111o..",
                    "...oo111111oo...",
                    ".....oooooo.....",
                    "................",
                    "................"));

    /** Shape names accepted by {@link #draw}. */
    public static final List<String> NAMES = List.of("gem", "ingot", "orb");

    private Shapes() {
    }

    /**
     * @param ramp    shades, darkest first; 4 or 5 colours suit these shapes
     * @param outline outline colour, or null to use the ramp's darkest shade
     */
    public static Texture draw(String name, Ramp ramp, Integer outline) {
        List<String> rows = TEMPLATES.get(name);
        if (rows == null) {
            throw new TextureException("unknown shape \"" + name + "\"; use one of " + String.join(", ", NAMES));
        }
        int edge = outline == null ? ramp.darkest() : outline;
        int top = ramp.size() - 1;
        int first = outline == null && ramp.size() > 3 ? 1 : 0;
        int mid = Math.max(first, top / 2);
        // Shade level 1..5 to ramp index: the base colour fills the body, light and glint sit above it.
        int[] index = {Math.max(first, mid - 2), Math.max(first, mid - 1), mid, Math.min(top, mid + 1), top};
        Texture t = Texture.square(16);
        for (int y = 0; y < 16; y++) {
            String row = rows.get(y);
            for (int x = 0; x < 16; x++) {
                char c = row.charAt(x);
                if (c == 'o') {
                    t.set(x, y, edge);
                } else if (c != '.') {
                    t.set(x, y, ramp.get(index[c - '1']));
                }
            }
        }
        return t;
    }
}
