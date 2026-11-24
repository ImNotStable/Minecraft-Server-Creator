package io.github.imnotstable.msc.stdlib.vanilla;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.imnotstable.msc.stdlib.cmd.CommandTree;
import io.github.imnotstable.msc.stdlib.perm.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server commands from a {@link CommandTree}: subcommands, permissions, player-only checks, cooldowns, usage and tab
 * completion, registered with the game's command dispatcher (again after every {@code /reload}, which rebuilds it).
 * Permission nodes go through {@link #permissions(Permissions)} when set, else they need an operator (level 2).
 *
 * <pre>{@code
 * Cmd.register(CommandTree.create("spawn").playerOnly().executes(c -> Teleports.to(player(c), spawn)));
 * }</pre>
 */
public final class Cmd {
    private static final List<CommandTree> TREES = new ArrayList<>();
    private static Permissions permissions;

    private Cmd() {}

    /** Use these ranks for permission nodes instead of operator levels. */
    public static void permissions(Permissions perms) {
        permissions = perms;
    }

    /** Register a command; works before the server starts and while it runs (players get the new command list). */
    public static synchronized void register(CommandTree tree) {
        TREES.add(tree);
        MinecraftServer server = Hooks.serverOrNull();
        if (server != null) {
            attach(server.getCommands().getDispatcher(), tree);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(p);
            }
        }
    }

    /** The player who ran a command; executors of playerOnly commands always have one. */
    public static ServerPlayer player(CommandTree.Ctx ctx) {
        UUID id = ctx.player();
        ServerPlayer p = Hooks.server().getPlayerList().getPlayer(id);
        if (p == null) {
            throw new CommandTree.Fail("You are not online.");
        }
        return p;
    }

    static synchronized void attachAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (CommandTree t : TREES) {
            attach(dispatcher, t);
        }
    }

    private static void attach(CommandDispatcher<CommandSourceStack> dispatcher, CommandTree tree) {
        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(literal(tree.name(), tree));
        for (String alias : tree.aliases()) {
            dispatcher.register(Commands.literal(alias).redirect(node).executes(c -> run(tree, c, alias)));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name, CommandTree tree) {
        return Commands.literal(name)
                .requires(s -> tree.permission() == null || permitted(s, tree.permission()))
                .executes(c -> run(tree, c, name))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((c, b) -> {
                            String typed = b.getRemaining();
                            String[] args = typed.isEmpty() ? new String[]{""} : typed.split(" ", -1);
                            int start = b.getStart() + typed.lastIndexOf(' ') + 1;
                            var sub = b.createOffset(start);
                            for (String s : tree.complete(source(c.getSource()), args)) {
                                sub.suggest(s);
                            }
                            return sub.buildFuture();
                        })
                        .executes(c -> run(tree, c, name)));
    }

    private static int run(CommandTree tree, CommandContext<CommandSourceStack> c, String label) {
        String raw;
        try {
            raw = StringArgumentType.getString(c, "args");
        } catch (IllegalArgumentException e) {
            raw = "";
        }
        String[] args = raw.isBlank() ? new String[0] : raw.trim().split("\\s+");
        return tree.dispatch(source(c.getSource()), label, args) ? 1 : 0;
    }

    static boolean permitted(CommandSourceStack s, String node) {
        ServerPlayer p = s.getPlayer();
        if (p == null) {
            return true; // the console
        }
        if (permissions != null) {
            return permissions.has(p.getUUID(), node);
        }
        return Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(s);
    }

    private static CommandTree.Source source(CommandSourceStack s) {
        return new CommandTree.Source() {
            @Override
            public UUID id() {
                ServerPlayer p = s.getPlayer();
                return p == null ? null : p.getUUID();
            }

            @Override
            public String name() {
                return s.getTextName();
            }

            @Override
            public boolean permitted(String node) {
                return Cmd.permitted(s, node);
            }

            @Override
            public void reply(String text) {
                s.sendSystemMessage(Component.literal(text));
            }

            @Override
            public void fail(String text) {
                s.sendSystemMessage(Component.literal(text));
            }
        };
    }
}
