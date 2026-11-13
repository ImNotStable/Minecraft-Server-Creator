package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;

import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

@EnvTest
class DevTeleportTest {
    @Test
    void teleportsAPlayerByName(Env env) {
        Instance world = env.createFlatInstance();
        Player player = env.createPlayer(world, new Pos(0, 42, 0, 90, 10));
        var commands = env.process().command();
        assertThat(DevTeleport.registerIfMissing(commands, env.process().connection()::getOnlinePlayerByUsername))
                .isTrue();

        commands.execute(commands.getConsoleSender(), "tp " + player.getUsername() + " 2.5 101 2.5");

        assertThat(player.getPosition()).isEqualTo(new Pos(2.5, 101, 2.5, 90, 10));
    }

    @Test
    void keepsTheServersOwnTp(Env env) {
        Command own = new Command("tp");
        env.process().command().register(own);

        assertThat(DevTeleport.registerIfMissing(env.process().command(), name -> null)).isFalse();
        assertThat(env.process().command().getCommand("tp")).isSameAs(own);
    }
}
