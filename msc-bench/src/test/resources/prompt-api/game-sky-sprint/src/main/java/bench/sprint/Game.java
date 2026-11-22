package bench.sprint;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

final class Game {
    Game(GameConfig config, Instance world, Progress progress, Runnable onChange) {
    }

    void applyWorldRules() {
    }

    Instance world() {
        throw new UnsupportedOperationException();
    }

    Stage stage(Player player) {
        throw new UnsupportedOperationException();
    }
}
