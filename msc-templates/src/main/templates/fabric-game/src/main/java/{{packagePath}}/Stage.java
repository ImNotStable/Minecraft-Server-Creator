package {{packageName}};

import java.util.Objects;

/**
 * One step of the game: where players (re)spawn while on it, and the box that completes it ({@code goal} may be
 * null: then only code or {@code /game next} completes the stage). Add your own per-stage settings here
 * (waves, timers, rewards) and set them in {@link GameConfig#game()}.
 */
public record Stage(String name, Spot spawn, Box goal) {
    public Stage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(spawn, "spawn");
        if (name.isBlank() || name.contains(" ")) {
            throw new IllegalArgumentException("stage name must be one word, got '" + name + "'");
        }
    }

    /** A stage without a goal box. */
    public Stage(String name, Spot spawn) {
        this(name, spawn, null);
    }
}
