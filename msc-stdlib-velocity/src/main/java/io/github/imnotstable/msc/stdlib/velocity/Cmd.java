package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.imnotstable.msc.stdlib.cmd.CommandTree;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.UUID;

/**
 * Proxy commands from a {@link CommandTree}: subcommands, permissions (Velocity's own, as granted by a permission
 * plugin such as LuckPerms), player-only checks, cooldowns, usage and tab completion.
 *
 * <pre>{@code
 * Cmd.register(CommandTree.create("hub").playerOnly().executes(c -> Servers.send(Cmd.player(c), "lobby")));
 * }</pre>
 */
public final class Cmd {
    private Cmd() {}

    public static void register(CommandTree tree) {
        var manager = Stdlib.proxy().getCommandManager();
        var meta = manager.metaBuilder(tree.name()).aliases(tree.aliases().toArray(String[]::new))
                .plugin(Stdlib.plugin()).build();
        manager.register(meta, new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                tree.dispatch(source(invocation.source()), invocation.alias(), invocation.arguments());
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                String[] args = invocation.arguments();
                return tree.complete(source(invocation.source()), args.length == 0 ? new String[]{""} : args);
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                return tree.permission() == null || invocation.source().hasPermission(tree.permission());
            }
        });
    }

    /** The player who ran a command; executors of playerOnly commands always have one. */
    public static Player player(CommandTree.Ctx ctx) {
        UUID id = ctx.player();
        return Stdlib.proxy().getPlayer(id).orElseThrow(() -> new CommandTree.Fail("You are not online."));
    }

    private static CommandTree.Source source(CommandSource s) {
        return new CommandTree.Source() {
            @Override
            public UUID id() {
                return s instanceof Player p ? p.getUniqueId() : null;
            }

            @Override
            public String name() {
                return s instanceof Player p ? p.getUsername() : "console";
            }

            @Override
            public boolean permitted(String node) {
                return s.hasPermission(node);
            }

            @Override
            public void reply(String text) {
                s.sendMessage(Component.text(text));
            }

            @Override
            public void fail(String text) {
                s.sendMessage(Component.text(text, NamedTextColor.RED));
            }
        };
    }
}
