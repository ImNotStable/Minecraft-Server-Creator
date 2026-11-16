package {{packageName}};

import java.util.Objects;
import net.minestom.server.coordinate.Pos;

/**
 * One step of the game: where players (re)spawn while on it, and the box that completes it ({@code goal} may be
 * null: then only code or {@code /game next} completes the stage). Add your own per-stage settings here
 * (waves, timers, rewards) and set them in {@link GameConfig#game()}.
 */
public record Stage(String name, Pos spawn, Box goal) {
    public Stage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(spawn, "spawn");
        if (!Box.inWorld(spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch())) {
            throw new IllegalArgumentException("stage " + name + ": spawn " + spawn
                    + " is not finite or outside the world (x and z within " + Box.MAX_XZ + ", y " + Box.MIN_Y + " to "
                    + Box.MAX_Y + ", yaw within 360, pitch within 90)");
        }
        if (!isName(name)) {
            throw new IllegalArgumentException("stage name must be " + NAME_RULE + ", got '" + name + "'");
        }
    }

    /** What a stage name must be, as the error for a bad one says it. */
    static final String NAME_RULE = "one word of visible characters (no spaces, no §)";

    /**
     * Whether {@code name} can be a stage name: one word that {@code /game stage <name>} can select and chat can type.
     * Commands split on spaces, so no whitespace (nor a no-break space); no control or invisible formatting character,
     * which chat cannot type and which make two names look the same; no {@code §}, which chat refuses.
     * SharedRulesTest.STAGE_NAMES lists names it takes and refuses.
     */
    static boolean isName(String name) {
        return !name.isEmpty() && name.codePoints().allMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)
                && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                && Character.getType(c) != Character.SURROGATE && c != '§');
    }

    /** Whether two stage names are the same name: {@code /game stage} ignores case, so names must differ in more. */
    static boolean sameName(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /** A stage without a goal box. */
    public Stage(String name, Pos spawn) {
        this(name, spawn, null);
    }
}
