package bench.sprint;

import java.util.List;
import java.util.function.Function;
import net.minestom.server.entity.Player;

final class GameCommand {
    record Reply(boolean ok, String text) {
    }

    GameCommand(Game game, Function<String, Player> players) {
    }

    List<Reply> handle(Player player, String[] args) {
        throw new UnsupportedOperationException();
    }
}
