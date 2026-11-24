package io.github.imnotstable.msc.stdlib.bungeecord;

import io.github.imnotstable.msc.stdlib.cmd.CommandTree;
import java.util.UUID;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

/**
 * Proxy commands from a {@link CommandTree}: subcommands, permissions (BungeeCord's own, as config.yml or a permission
 * plugin such as LuckPerms grants them), player-only checks, cooldowns, usage and tab completion.
 *
 * <pre>{@code
 * Cmd.register(CommandTree.create("hub").playerOnly().executes(c -> Servers.send(Cmd.player(c), "lobby")));
 * }</pre>
 */
public final class Cmd {
    private Cmd() {}

    public static void register(CommandTree tree) {
        Stdlib.proxy().getPluginManager().registerCommand(Stdlib.plugin(), command(tree));
    }

    /** The BungeeCord command for a tree, unregistered; {@link #register} is what plugins call. */
    static Command command(CommandTree tree) {
        return new TreeCommand(tree);
    }

    /** The player who ran a command; executors of playerOnly commands always have one. */
    public static ProxiedPlayer player(CommandTree.Ctx ctx) {
        UUID id = ctx.player();
        ProxiedPlayer p = id == null ? null : Stdlib.proxy().getPlayer(id);
        if (p == null) {
            throw new CommandTree.Fail("You are not online.");
        }
        return p;
    }

    private static final class TreeCommand extends Command implements TabExecutor {
        private final CommandTree tree;

        TreeCommand(CommandTree tree) {
            // The permission is checked by the tree, so BungeeCord's own check is left out (null).
            super(tree.name(), null, tree.aliases().toArray(String[]::new));
            this.tree = tree;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (tree.permission() != null && !sender.hasPermission(tree.permission())) {
                source(sender).fail("You do not have permission to use this command.");
                return;
            }
            tree.dispatch(source(sender), getName(), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            if (tree.permission() != null && !sender.hasPermission(tree.permission())) {
                return java.util.List.of();
            }
            return tree.complete(source(sender), args.length == 0 ? new String[]{""} : args);
        }
    }

    static CommandTree.Source source(CommandSender s) {
        return new CommandTree.Source() {
            @Override
            public UUID id() {
                return s instanceof ProxiedPlayer p ? p.getUniqueId() : null;
            }

            @Override
            public String name() {
                return s instanceof ProxiedPlayer p ? p.getName() : "console";
            }

            @Override
            public boolean permitted(String node) {
                return s.hasPermission(node);
            }

            @Override
            public void reply(String text) {
                s.sendMessage(new TextComponent(text));
            }

            @Override
            public void fail(String text) {
                TextComponent c = new TextComponent(text);
                c.setColor(ChatColor.RED);
                s.sendMessage(c);
            }
        };
    }
}
