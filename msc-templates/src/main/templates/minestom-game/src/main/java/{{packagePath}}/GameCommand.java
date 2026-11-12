package {{packageName}};

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

/**
 * {@code /game}: admin and debug commands, for you and for the AI testing the game over the console.
 * Every reply goes to the sender (never a broadcast), so console callers ({@code server_command}) see it.
 * <pre>
 * game status                    game state and every online player's stage
 * game stage &lt;name|n&gt; [player]  jump to a stage
 * game next [player]             complete the current stage
 * game reset [player]            forget progress, back to stage 1 with a fresh kit
 * game kit [player]              give the kit again
 * </pre>
 * Players may use it when they are in {@link GameConfig#admins()}; the console always can.
 * Add a subcommand to {@link #handle} for every piece of state you will want to check or skip to while testing.
 */
public final class GameCommand extends Command {
    static final String USAGE = "usage: game status | stage <name|n> [player] | next [player] | reset [player] | kit [player]";

    private final Game game;
    private final Function<String, Player> findPlayer;

    /** @param findPlayer looks up an online player by name, or returns null */
    public GameCommand(Game game, Function<String, Player> findPlayer) {
        super("game");
        this.game = game;
        this.findPlayer = findPlayer;
        setCondition((sender, input) -> !(sender instanceof Player p) || game.config().isAdmin(p.getUsername()));
        setDefaultExecutor((sender, context) -> reply(sender, handle(sender instanceof Player p ? p : null, new String[0])));
        ArgumentStringArray args = ArgumentType.StringArray("args");
        addSyntax((sender, context) -> reply(sender, handle(sender instanceof Player p ? p : null, context.get(args))), args);
    }

    /** One reply line; {@code ok} is shown green, otherwise red. */
    public record Reply(boolean ok, String text) {
    }

    private static void reply(net.minestom.server.command.CommandSender sender, List<Reply> lines) {
        lines.forEach(r -> sender.sendMessage(Component.text(r.text(), r.ok() ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    /** Runs {@code /game args} for {@code self} (null for the console) and returns the reply. Tests call this. */
    public List<Reply> handle(Player self, String[] args) {
        List<Reply> out = new ArrayList<>();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> game.status(online()).forEach(line -> out.add(new Reply(true, line)));
            case "stage" -> {
                if (args.length < 2) {
                    out.add(new Reply(false, "usage: game stage <name|n> [player]"));
                    break;
                }
                int index = game.config().stageIndex(args[1]);
                if (index < 0) {
                    out.add(new Reply(false, "no stage " + args[1] + ". Stages: " + stageNames()));
                    break;
                }
                Player target = target(self, args, 2, out);
                if (target != null) {
                    game.moveTo(target, index, "admin");
                    out.add(new Reply(true, target.getUsername() + " is on stage " + (index + 1) + " " + game.stage(target).name()));
                }
            }
            case "next" -> {
                Player target = target(self, args, 1, out);
                if (target != null) {
                    game.advance(target, "admin");
                    out.add(new Reply(true, target.getUsername() + " is on stage " + (game.stageIndex(target.getUuid()) + 1)
                            + " " + game.stage(target).name()));
                }
            }
            case "reset" -> {
                Player target = target(self, args, 1, out);
                if (target != null) {
                    game.reset(target);
                    out.add(new Reply(true, target.getUsername() + " was reset to stage 1"));
                }
            }
            case "kit" -> {
                Player target = target(self, args, 1, out);
                if (target != null) {
                    game.config().kit().give(target);
                    out.add(new Reply(true, target.getUsername() + " got the kit"));
                }
            }
            default -> out.add(new Reply(false, USAGE));
        }
        return out;
    }

    /** The player named at {@code args[i]}, or {@code self} when they gave no name. */
    private Player target(Player self, String[] args, int i, List<Reply> out) {
        if (args.length > i) {
            Player p = findPlayer.apply(args[i]);
            if (p == null) {
                out.add(new Reply(false, "no online player " + args[i]));
            }
            return p;
        }
        if (self != null) {
            return self;
        }
        out.add(new Reply(false, "name a player: " + String.join(" ", Arrays.copyOf(args, i)) + " <player>"));
        return null;
    }

    private List<Player> online() {
        return List.copyOf(game.world().getPlayers());
    }

    private List<String> stageNames() {
        return game.config().stages().stream().map(Stage::name).toList();
    }
}
