package {{packageName}};

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
 * Needs permission level 2 (ops, the console, RCON). Add a subcommand to {@link #handle} for every piece of
 * state you will want to check or skip to while testing.
 */
public final class GameCommand {
    static final String USAGE = "usage: game status | stage <name|n> [player] | next [player] | reset [player] | kit [player]";
    private static final List<String> SUBCOMMANDS = List.of("status", "stage", "next", "reset", "kit");

    private final Game game;

    public GameCommand(Game game) {
        this.game = game;
    }

    /** One reply line; {@code ok} is a success message, otherwise a failure. */
    public record Reply(boolean ok, String text) {
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        SuggestionProvider<CommandSourceStack> suggest = (context, builder) -> {
            String typed = builder.getRemaining();
            String[] words = words(typed, true);
            // Suggest for the last word only: the greedy argument holds the whole line after "game ".
            SuggestionsBuilder word = builder.createOffset(
                    builder.getStart() + typed.length() - words[words.length - 1].length());
            return SharedSuggestionProvider.suggest(suggestions(context.getSource(), words), word);
        };
        dispatcher.register(Commands.literal("game")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> run(context, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString()).suggests(suggest)
                        .executes(context -> run(context, StringArgumentType.getString(context, "args")))));
    }

    private int run(CommandContext<CommandSourceStack> context, String args) {
        CommandSourceStack source = context.getSource();
        List<Reply> replies = handle(source, words(args, false));
        for (Reply r : replies) {
            if (r.ok()) {
                source.sendSuccess(() -> Component.literal(r.text()), false);
            } else {
                source.sendFailure(Component.literal(r.text()));
            }
        }
        return replies.stream().allMatch(Reply::ok) ? 1 : 0;
    }

    /** Runs {@code /game args} for {@code source} and returns the reply. */
    public List<Reply> handle(CommandSourceStack source, String[] args) {
        List<Reply> out = new ArrayList<>();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> game.status(online(source)).forEach(line -> out.add(new Reply(true, line)));
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
                ServerPlayer target = target(source, args, 2, out);
                if (target != null) {
                    game.moveTo(target, index, "admin");
                    out.add(new Reply(true, target.getPlainTextName() + " is on stage " + (index + 1) + " "
                            + game.stage(target).name()));
                }
            }
            case "next" -> {
                ServerPlayer target = target(source, args, 1, out);
                if (target != null) {
                    game.advance(target, "admin");
                    out.add(new Reply(true, target.getPlainTextName() + " is on stage "
                            + (game.stageIndex(target.getUUID()) + 1) + " " + game.stage(target).name()));
                }
            }
            case "reset" -> {
                ServerPlayer target = target(source, args, 1, out);
                if (target != null) {
                    game.reset(target);
                    out.add(new Reply(true, target.getPlainTextName() + " was reset to stage 1"));
                }
            }
            case "kit" -> {
                ServerPlayer target = target(source, args, 1, out);
                if (target != null) {
                    game.config().kit().give(target);
                    out.add(new Reply(true, target.getPlainTextName() + " got the kit"));
                }
            }
            default -> out.add(new Reply(false, USAGE));
        }
        return out;
    }

    /** The player named at {@code args[i]}, or the sender when they are a player and gave no name. */
    private static ServerPlayer target(CommandSourceStack source, String[] args, int i, List<Reply> out) {
        if (args.length > i) {
            MinecraftServer server = source.getServer();
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayerByName(args[i]);
            if (p == null) {
                out.add(new Reply(false, "no online player " + args[i]));
            }
            return p;
        }
        if (source.getPlayer() != null) {
            return source.getPlayer();
        }
        out.add(new Reply(false, "name a player: " + String.join(" ", Arrays.copyOf(args, i)) + " <player>"));
        return null;
    }

    /** Online players; none when there is no server (unit tests). */
    private static List<ServerPlayer> online(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        return server == null ? List.of() : List.copyOf(server.getPlayerList().getPlayers());
    }

    /** Suggestions for the word being typed: subcommands first, then stage names, then player names. */
    private List<String> suggestions(CommandSourceStack source, String[] words) {
        if (words.length <= 1) {
            return SUBCOMMANDS;
        }
        if (words.length == 2 && words[0].equalsIgnoreCase("stage")) {
            return stageNames();
        }
        return online(source).stream().map(ServerPlayer::getPlainTextName).toList();
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
}
