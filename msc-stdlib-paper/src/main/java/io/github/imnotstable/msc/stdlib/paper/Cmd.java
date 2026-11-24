package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.util.Format;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Commands with subcommands, permissions, player-only checks, cooldowns, usage and tab completion, in a few lines.
 * For typed Brigadier argument trees use Paper's {@code Commands.literal(...)} directly.
 *
 * <pre>{@code
 * Cmd.create("party").description("Manage your party").playerOnly()
 *         .sub(Cmd.create("invite").usage("<player>").tab(c -> c.onlinePlayerNames())
 *                 .executes(c -> parties.invite(c.player(), c.argPlayer(0))))
 *         .sub(Cmd.create("leave").executes(c -> parties.leave(c.player())))
 *         .register(this);       // in onEnable
 * }</pre>
 * In an executor, {@code throw new Cmd.Fail("text")} sends red text and stops.
 */
public final class Cmd {
    private final String name;
    private String description = "";
    private final List<String> aliases = new ArrayList<>();
    private String permission;
    private boolean playerOnly;
    private String usage = "";
    private Duration cooldown;
    private final Cooldowns<UUID> cooldowns = new Cooldowns<>();
    private Consumer<Ctx> executor;
    private Function<Ctx, Collection<String>> tab;
    private final Map<String, Cmd> subs = new LinkedHashMap<>();

    private Cmd(String name) {
        this.name = name.toLowerCase(Locale.ROOT);
    }

    public static Cmd create(String name) {
        return new Cmd(name);
    }

    /** Stops the command with a red message to the sender. */
    public static final class Fail extends RuntimeException {
        public Fail(String miniMessage) {
            super(miniMessage, null, false, false);
        }
    }

    /** What an executor gets: who ran it and the arguments after the (sub)command. */
    public record Ctx(CommandSender sender, String[] args, String label) {
        /** The player who ran it ({@link #playerOnly} commands always have one). */
        public Player player() {
            if (sender instanceof Player p) {
                return p;
            }
            throw new Fail("Only players can use this.");
        }

        public boolean isPlayer() {
            return sender instanceof Player;
        }

        /** Argument {@code i}, or {@link Fail} with the usage when missing. */
        public String arg(int i) {
            if (i >= args.length) {
                throw new Fail("Missing argument " + (i + 1) + ". Usage: " + label);
            }
            return args[i];
        }

        public String arg(int i, String fallback) {
            return i < args.length ? args[i] : fallback;
        }

        /** Every argument from {@code i} joined by spaces (a message, a name with spaces). */
        public String rest(int i) {
            return i >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, i, args.length));
        }

        public int argInt(int i) {
            try {
                return Integer.parseInt(arg(i));
            } catch (NumberFormatException e) {
                throw new Fail("'" + args[i] + "' is not a whole number.");
            }
        }

        public int argInt(int i, int fallback) {
            return i < args.length ? argInt(i) : fallback;
        }

        public double argDouble(int i) {
            try {
                return Double.parseDouble(arg(i));
            } catch (NumberFormatException e) {
                throw new Fail("'" + args[i] + "' is not a number.");
            }
        }

        /** An online player by name. */
        public Player argPlayer(int i) {
            Player p = Bukkit.getPlayerExact(arg(i));
            if (p == null) {
                throw new Fail("No player '" + args[i] + "' online.");
            }
            return p;
        }

        public Duration argDuration(int i) {
            try {
                return io.github.imnotstable.msc.stdlib.time.Durations.parse(arg(i));
            } catch (IllegalArgumentException e) {
                throw new Fail(e.getMessage());
            }
        }

        public void reply(String miniMessage, Object... placeholders) {
            sender.sendMessage(Text.mm(miniMessage, placeholders));
        }

        /** Names of online players, for {@link Cmd#tab}. */
        public List<String> onlinePlayerNames() {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
    }

    public Cmd description(String text) {
        this.description = text;
        return this;
    }

    public Cmd aliases(String... names) {
        aliases.addAll(List.of(names));
        return this;
    }

    public Cmd permission(String node) {
        this.permission = node;
        return this;
    }

    public Cmd playerOnly() {
        this.playerOnly = true;
        return this;
    }

    /** Shown after the command name when arguments are wrong: {@code "<player> [amount]"}. */
    public Cmd usage(String args) {
        this.usage = args;
        return this;
    }

    /** Time between uses per player. */
    public Cmd cooldown(Duration time) {
        this.cooldown = time;
        return this;
    }

    public Cmd executes(Consumer<Ctx> action) {
        this.executor = action;
        return this;
    }

    /** Suggestions for the argument being typed (the last of {@code ctx.args()}); filtered by what was typed. */
    public Cmd tab(Function<Ctx, Collection<String>> suggestions) {
        this.tab = suggestions;
        return this;
    }

    public Cmd sub(Cmd sub) {
        subs.put(sub.name, sub);
        sub.aliases.forEach(a -> subs.put(a.toLowerCase(Locale.ROOT), sub));
        return this;
    }

    /** Registers with Paper; call in {@code onEnable}. */
    public void register(Plugin plugin) {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, e -> e.registrar().register(name, description, aliases,
                basic()));
    }

    /** As a Paper {@link BasicCommand}, for registering yourself. */
    public BasicCommand basic() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                run(source.getSender(), args, "/" + name);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                return complete(source.getSender(), args);
            }

            @Override
            public String permission() {
                return permission;
            }
        };
    }

    /** Runs the command as {@code sender} would: for tests and for calling one command from another. */
    public void run(CommandSender sender, String[] args, String label) {
        if (permission != null && !sender.hasPermission(permission)) {
            sender.sendMessage(Text.mm("You don't have permission."));
            return;
        }
        if (playerOnly && !(sender instanceof Player)) {
            sender.sendMessage(Text.mm("Only players can use this."));
            return;
        }
        if (args.length > 0 && subs.containsKey(args[0].toLowerCase(Locale.ROOT))) {
            Cmd sub = subs.get(args[0].toLowerCase(Locale.ROOT));
            sub.run(sender, Arrays.copyOfRange(args, 1, args.length), label + " " + sub.name);
            return;
        }
        try {
            if (executor == null) {
                sender.sendMessage(Text.mm(label + " " + String.join(" | ", subs.values()
                        .stream().distinct().map(s -> s.name + (s.usage.isEmpty() ? "" : " " + s.usage)).toList())));
                return;
            }
            if (cooldown != null && sender instanceof Player p && !cooldowns.tryUse(p.getUniqueId(), cooldown)) {
                throw new Fail("Wait " + Format.duration(cooldowns.remaining(p.getUniqueId())) + ".");
            }
            executor.accept(new Ctx(sender, args, label + (usage.isEmpty() ? "" : " " + usage)));
        } catch (Fail f) {
            sender.sendMessage(Text.mm(f.getMessage()));
        }
    }

    Collection<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1 && !subs.isEmpty()) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return subs.keySet().stream().filter(s -> s.startsWith(typed)).toList();
        }
        if (args.length > 1 && subs.containsKey(args[0].toLowerCase(Locale.ROOT))) {
            return subs.get(args[0].toLowerCase(Locale.ROOT)).complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (tab == null) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return tab.apply(new Ctx(sender, args, name)).stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
