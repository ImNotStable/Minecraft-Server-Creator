package {{packageName}};

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /game}: admin and debug commands, for you and for the AI testing the game over the console.
 * Every reply goes to the sender (never a broadcast), so console and RCON callers see it.
 * <pre>
 * game status                    game state and every online player's stage
 * game stage &lt;name|n&gt; [player]  jump to a stage
 * game next [player]             complete the current stage
 * game reset [player]            forget progress, back to stage 1 with a fresh kit
 * game kit [player]              give the kit again
 * </pre>
 * Add a subcommand for every piece of state you will want to check or skip to while testing.
 */
public final class GameCommand implements BasicCommand {
    static final String USAGE = "usage: game status | stage <name|n> [player] | next [player] | reset [player] | kit [player]";
    private static final List<String> SUBCOMMANDS = List.of("status", "stage", "next", "reset", "kit");

    private final Game game;
    private final Server server;
    private final String permission;

    public GameCommand(Game game, Server server, String permission) {
        this.game = game;
        this.server = server;
        this.permission = permission;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        args = words(String.join(" ", args), false);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> game.status(List.copyOf(server.getOnlinePlayers())).forEach(sender::sendMessage);
            case "stage" -> {
                if (args.length < 2) {
                    error(sender, "usage: game stage <name|n> [player]");
                    return;
                }
                int index = game.config().stageIndex(args[1]);
                if (index < 0) {
                    error(sender, "no stage " + args[1] + ". Stages: " + stageNames());
                    return;
                }
                Player target = target(sender, args, 2);
                if (target != null) {
                    game.moveTo(target, index, "admin");
                    ok(sender, target.getName() + " is on stage " + (index + 1) + " " + game.stage(target).name());
                }
            }
            case "next" -> {
                Player target = target(sender, args, 1);
                if (target != null) {
                    game.advance(target, "admin");
                    ok(sender, target.getName() + " is on stage " + (game.stageIndex(target.getUniqueId()) + 1)
                            + " " + game.stage(target).name());
                }
            }
            case "reset" -> {
                Player target = target(sender, args, 1);
                if (target != null) {
                    game.reset(target);
                    ok(sender, target.getName() + " was reset to stage 1");
                }
            }
            case "kit" -> {
                Player target = target(sender, args, 1);
                if (target != null) {
                    game.config().kit().give(target);
                    ok(sender, target.getName() + " got the kit");
                }
            }
            default -> error(sender, USAGE);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] typed) {
        return suggestions(typed);
    }

    /** Suggestions for the word being typed: subcommands first, then stage names, then player names. */
    List<String> suggestions(String[] typed) {
        String[] args = words(String.join(" ", typed), true);
        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stage")) {
            return filter(stageNames(), args);
        }
        List<String> players = new ArrayList<>();
        server.getOnlinePlayers().forEach(p -> players.add(p.getName()));
        return filter(players, args);
    }

    @Override
    public String permission() {
        return permission;
    }

    /** The player named at {@code args[i]}, or the sender when they are a player and gave no name. */
    private @Nullable Player target(CommandSender sender, String[] args, int i) {
        if (args.length > i) {
            Player p = server.getPlayerExact(args[i]);
            if (p == null) {
                error(sender, "no online player " + args[i]);
            }
            return p;
        }
        if (sender instanceof Player p) {
            return p;
        }
        error(sender, "name a player: " + String.join(" ", Arrays.copyOf(args, i)) + " <player>");
        return null;
    }

    /**
     * The words of a command line, split on runs of whitespace, so {@code stage  arena} is two words: the same in every
     * game template. With {@code typing} (tab completion), a trailing space starts an empty last word, the one being
     * typed, and there is always a last word.
     */
    static String[] words(String line, boolean typing) {
        String s = typing ? line.stripLeading() : line.strip();
        if (s.isEmpty()) {
            return typing ? new String[] {""} : new String[0];
        }
        return s.split("\\s+", typing ? -1 : 0);
    }

    private List<String> stageNames() {
        return game.config().stages().stream().map(Stage::name).toList();
    }

    private static List<String> filter(List<String> options, String[] args) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static void ok(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
