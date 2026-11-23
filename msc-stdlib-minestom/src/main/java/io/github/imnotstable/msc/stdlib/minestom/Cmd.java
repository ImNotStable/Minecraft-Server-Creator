package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.cmd.CommandTree;
import io.github.imnotstable.msc.stdlib.perm.Permissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

import java.util.UUID;

/**
 * Server commands from a {@link CommandTree}: subcommands, permissions, player-only checks, cooldowns, usage and tab
 * completion. Minestom has no permission system: nodes go through {@link #permissions(Permissions)}; without it, a
 * command with a permission is console-only.
 *
 * <pre>{@code
 * Cmd.register(CommandTree.create("spawn").playerOnly().executes(c -> Cmd.player(c).teleport(spawn)));
 * }</pre>
 */
public final class Cmd {
    private static Permissions permissions;

    private Cmd() {}

    public static void permissions(Permissions perms) {
        permissions = perms;
    }

    public static void register(CommandTree tree) {
        Command command = new Command(tree.name(), tree.aliases().toArray(String[]::new));
        command.setCondition((sender, input) -> tree.permission() == null || permitted(sender, tree.permission()));
        command.setDefaultExecutor((sender, ctx) -> tree.dispatch(source(sender), tree.name(), new String[0]));
        ArgumentStringArray args = ArgumentType.StringArray("args");
        args.setSuggestionCallback((sender, ctx, suggestion) -> {
            String input = suggestion.getInput();
            int space = input.indexOf(' ');
            String rest = space < 0 ? "" : input.substring(space + 1);
            for (String s : tree.complete(source(sender), rest.split(" ", -1))) {
                suggestion.addEntry(new SuggestionEntry(s));
            }
        });
        command.addSyntax((sender, ctx) -> tree.dispatch(source(sender), tree.name(), ctx.get(args)), args);
        MinecraftServer.getCommandManager().register(command);
    }

    /** The player who ran a command; executors of playerOnly commands always have one. */
    public static Player player(CommandTree.Ctx ctx) {
        UUID id = ctx.player();
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id);
        if (p == null) {
            throw new CommandTree.Fail("You are not online.");
        }
        return p;
    }

    static boolean permitted(CommandSender sender, String node) {
        if (!(sender instanceof Player p)) {
            return true;
        }
        return permissions != null && permissions.has(p.getUuid(), node);
    }

    private static CommandTree.Source source(CommandSender sender) {
        return new CommandTree.Source() {
            @Override
            public UUID id() {
                return sender instanceof Player p ? p.getUuid() : null;
            }

            @Override
            public String name() {
                return sender instanceof Player p ? p.getUsername() : "console";
            }

            @Override
            public boolean permitted(String node) {
                return Cmd.permitted(sender, node);
            }

            @Override
            public void reply(String text) {
                sender.sendMessage(Component.text(text));
            }

            @Override
            public void fail(String text) {
                sender.sendMessage(Component.text(text, NamedTextColor.RED));
            }
        };
    }
}
