package {{packageName}};

import org.jspecify.annotations.Nullable;

/**
 * One step of the game: where players (re)spawn while on it, and the box that completes it.
 * Add your own per-stage settings here (waves, timers, rewards): add their keys to {@code GameConfig.STAGE_KEYS}
 * and read them in {@link GameConfig}.
 */
public record Stage(String name, Spot spawn, @Nullable Box goal) {
}
