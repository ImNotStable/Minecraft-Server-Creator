package io.github.imnotstable.msc.agent.minestom;

import java.util.function.Function;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.utils.location.RelativeVec;

/**
 * {@code /tp <player> <x> <y> <z>} on a dev server, as on every other platform: Minestom has no vanilla commands, and
 * the AI's test player moves with this one. Registered only while msc-core runs the server, and only when the
 * server has no {@code tp} of its own.
 */
final class DevTeleport {
    private DevTeleport() {
    }

    /** Register the command unless {@code tp} exists; true when it was registered. */
    static boolean registerIfMissing(CommandManager commands, Function<String, Player> players) {
        if (commands.commandExists("tp")) {
            return false;
        }
        commands.register(command(players));
        return true;
    }

    static Command command(Function<String, Player> players) {
        Command tp = new Command("tp");
        var name = ArgumentType.Word("player");
        var pos = ArgumentType.RelativeVec3("pos");
        tp.addSyntax((sender, context) -> {
            Player player = players.apply(context.get(name));
            if (player == null) {
                sender.sendMessage("No player was found");
                return;
            }
            RelativeVec target = context.get(pos);
            Vec to = target.from(player);
            Pos at = player.getPosition();
            player.teleport(new Pos(to.x(), to.y(), to.z(), at.yaw(), at.pitch()));
            sender.sendMessage(String.format(java.util.Locale.ROOT, "Teleported %s to %.2f, %.2f, %.2f",
                    player.getUsername(), to.x(), to.y(), to.z()));
        }, name, pos);
        return tp;
    }
}
