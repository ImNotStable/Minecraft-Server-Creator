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
        if (name.isBlank() || name.contains(" ")) {
            throw new IllegalArgumentException("stage name must be one word, got '" + name + "'");
        }
    }

    /** A stage without a goal box. */
    public Stage(String name, Pos spawn) {
        this(name, spawn, null);
    }
}
