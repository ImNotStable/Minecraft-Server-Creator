package io.github.imnotstable.msc.textures;

import java.util.List;
import java.util.Map;

/**
 * Shaded 16x16 item silhouettes, coloured from a ramp. Light comes from the top left as in vanilla
 * items. The templates use {@code o} for the outline, {@code 1} deep shadow, {@code 2} shadow,
 * {@code 3} base colour, {@code 4} light, {@code 5} glint, and {@code .} for transparent. Tools and food
 * also have a second part, a handle or a bone, in {@code h} (shade) and {@code H} (light) from its own colour.
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
                    "................"),
            "sword", List.of(
                    "..............oo",
                    ".............o5o",
                    "............o54o",
                    "...........o54o.",
                    "..........o53o..",
                    ".........o53o...",
                    "........o53o....",
                    ".......o53o.....",
                    "..oo..o53o......",
                    "..o3oo53o.......",
                    "...o321o........",
                    "....oho2oo......",
                    "...ohHo.o3o.....",
                    "..ohHo...oo.....",
                    ".oohHo..........",
                    ".ooo............"),
            "pickaxe", List.of(
                    "................",
                    ".....oooooo.....",
                    "....o554443oo...",
                    ".....oo3332221o.",
                    ".......ooo3221o.",
                    ".........oH321o.",
                    "........oHho221o",
                    ".......oHho.o21o",
                    "......oHho..o21o",
                    ".....oHho....oo.",
                    "....oHho........",
                    "...oHho.........",
                    "..oHho..........",
                    ".oHho...........",
                    ".oho............",
                    "..o............."),
            "food", List.of(
                    "................",
                    "................",
                    ".........oooo...",
                    ".......oo4443o..",
                    "......o4543332o.",
                    ".....o45333332o.",
                    ".....o43333322o.",
                    ".....o33333321o.",
                    "......o3332221o.",
                    "......oo22211o..",
                    ".....oHhoooooo..",
                    "....oHho........",
                    "..ooHho.........",
                    ".oHHHo..........",
                    ".ohhho..........",
                    "..ooo..........."));

    /** Shape names accepted by {@link #draw}. */
    public static final List<String> NAMES = List.of("gem", "ingot", "orb", "sword", "pickaxe", "food");

    /** Default colour of the second part: wood for tools, bone for food. */
    private static final Map<String, Integer> SECOND = Map.of("sword", 0xFF6B4A28, "pickaxe", 0xFF6B4A28,
            "food", 0xFFE8DCC0);

    private Shapes() {
    }

    /**
     * @param ramp    shades, darkest first; 4 or 5 colours suit these shapes
     * @param outline outline colour, or null to use the ramp's darkest shade
     */
    public static Texture draw(String name, Ramp ramp, Integer outline) {
        return draw(name, ramp, null, outline);
    }

    /**
     * @param second colour of the handle or bone, or null for the shape's default; unused by shapes without one
     */
    public static Texture draw(String name, Ramp ramp, Integer second, Integer outline) {
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
        int base = second != null ? second : SECOND.getOrDefault(name, ramp.get(mid));
        Ramp part = Ramp.from(base, 3);
        Texture t = Texture.square(16);
        for (int y = 0; y < 16; y++) {
            String row = rows.get(y);
            for (int x = 0; x < 16; x++) {
                char c = row.charAt(x);
                if (c == 'o') {
                    t.set(x, y, edge);
                } else if (c == 'h') {
                    t.set(x, y, part.get(1));
                } else if (c == 'H') {
                    t.set(x, y, part.get(2));
                } else if (c != '.') {
                    t.set(x, y, ramp.get(index[c - '1']));
                }
            }
        }
        return t;
    }
}
