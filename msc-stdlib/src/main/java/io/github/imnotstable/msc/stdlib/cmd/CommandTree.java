package io.github.imnotstable.msc.stdlib.cmd;

import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.util.Format;

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
 * A command with subcommands, permissions, player-only checks, cooldowns, usage and tab completion, the same on every
 * platform: the adapters' {@code Cmd} (Fabric, Forge, NeoForge, Minestom, Velocity) register a tree with the server and
 * pass each run in as a {@link Source}. Paper keeps its own {@code Cmd} with the same shape.
 *
 * <pre>{@code
 * CommandTree party = CommandTree.create("party").description("Manage your party").playerOnly()
 *         .sub(CommandTree.create("invite").usage("<player>").permission("party.invite")
 *                 .tab(c -> onlineNames()).executes(c -> invite(c.source().id(), c.arg(0))))
 *         .sub(CommandTree.create("leave").cooldown(Duration.ofSeconds(5)).executes(c -> leave(c.source().id())));
 * }</pre>
 * In an executor, {@code throw new CommandTree.Fail("text")} tells the sender and stops.
 */
public final class CommandTree {
    /** Who ran a command, as the platform sees them. */
    public interface Source {
        /** The player's id, or null for the console. */
        UUID id();

        String name();

        default boolean isPlayer() {
            return id() != null;
        }

        /** Whether the sender may use a permission node (the platform decides how: ops, a permission plugin...). */
        boolean permitted(String node);

        void reply(String text);

        void fail(String text);
    }

    /** Stops the command with a message to the sender. */
    public static final class Fail extends RuntimeException {
        public Fail(String text) {
            super(text, null, false, false);
        }
    }

    /** One run: who, the arguments after the (sub)command, and the label path used. */
    public record Ctx(Source source, String[] args, String label) {
        public String arg(int i) {
            if (i >= args.length) {
                throw new Fail("Missing argument " + (i + 1) + ".");
            }
            return args[i];
        }

        public String arg(int i, String fallback) {
            return i < args.length ? args[i] : fallback;
        }

        public int argInt(int i) {
            try {
                return Integer.parseInt(arg(i));
            } catch (NumberFormatException e) {
                throw new Fail("Not a whole number: " + arg(i));
            }
        }

        public double argDouble(int i) {
            try {
                return Double.parseDouble(arg(i));
            } catch (NumberFormatException e) {
                throw new Fail("Not a number: " + arg(i));
            }
        }

        /** The arguments from {@code i} on, joined with spaces (a message, a name with spaces). */
        public String rest(int i) {
            return i >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, i, args.length));
        }

        public UUID player() {
            if (source.id() == null) {
                throw new Fail("Only players can use this.");
            }
            return source.id();
        }
    }

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
    private final Map<String, CommandTree> subs = new LinkedHashMap<>();

    private CommandTree(String name) {
        this.name = name.toLowerCase(Locale.ROOT);
    }

    public static CommandTree create(String name) {
        return new CommandTree(name);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public List<String> aliases() {
        return List.copyOf(aliases);
    }

    public String permission() {
        return permission;
    }

    public CommandTree description(String text) {
        this.description = text;
        return this;
    }

    public CommandTree aliases(String... names) {
        for (String n : names) {
            aliases.add(n.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    public CommandTree permission(String node) {
        this.permission = node;
        return this;
    }

    public CommandTree playerOnly() {
        this.playerOnly = true;
        return this;
    }

    /** The arguments, shown when the command is used wrong: {@code <player> [amount]}. */
    public CommandTree usage(String text) {
        this.usage = text;
        return this;
    }

    /** How long a player waits between uses. */
    public CommandTree cooldown(Duration wait) {
        this.cooldown = wait;
        return this;
    }

    public CommandTree executes(Consumer<Ctx> action) {
        this.executor = action;
        return this;
    }

    /** Suggestions for the current argument. */
    public CommandTree tab(Function<Ctx, Collection<String>> suggestions) {
        this.tab = suggestions;
        return this;
    }

    public CommandTree sub(CommandTree child) {
        subs.put(child.name, child);
        for (String a : child.aliases) {
            subs.putIfAbsent(a, child);
        }
        return this;
    }

    /** Run the command with the arguments after its name; returns whether it ran its executor. */
    public boolean dispatch(Source source, String label, String[] args) {
        if (permission != null && !source.permitted(permission)) {
            source.fail("You don't have permission to do that.");
            return false;
        }
        if (args.length > 0) {
            CommandTree sub = subs.get(args[0].toLowerCase(Locale.ROOT));
            if (sub != null) {
                return sub.dispatch(source, label + " " + sub.name, Arrays.copyOfRange(args, 1, args.length));
            }
        }
        if (playerOnly && !source.isPlayer()) {
            source.fail("Only players can use this.");
            return false;
        }
        if (executor == null) {
            source.fail(help(source, label));
            return false;
        }
        if (cooldown != null && source.isPlayer()) {
            Duration left = cooldowns.remaining(source.id());
            if (!left.isZero()) {
                source.fail("Wait " + Format.duration(left) + ".");
                return false;
            }
        }
        try {
            executor.accept(new Ctx(source, args, label));
            if (cooldown != null && source.isPlayer()) {
                cooldowns.set(source.id(), cooldown);
            }
            return true;
        } catch (Fail f) {
            source.fail(f.getMessage());
            return false;
        }
    }

    /** What the sender may run here: the usage or the subcommands they are allowed. */
    public String help(Source source, String label) {
        List<String> lines = new ArrayList<>();
        if (executor != null || !usage.isEmpty()) {
            lines.add("/" + label + (usage.isEmpty() ? "" : " " + usage));
        }
        subs.forEach((key, sub) -> {
            if (key.equals(sub.name) && (sub.permission == null || source.permitted(sub.permission))) {
                lines.add("/" + label + " " + sub.name + (sub.usage.isEmpty() ? "" : " " + sub.usage)
                        + (sub.description.isEmpty() ? "" : " - " + sub.description));
            }
        });
        return lines.isEmpty() ? "Usage: /" + label : String.join("\n", lines);
    }

    /** Suggestions for the last argument: subcommand names, then the executor's own, filtered by what is typed. */
    public List<String> complete(Source source, String[] args) {
        if (permission != null && !source.permitted(permission)) {
            return List.of();
        }
        if (args.length > 1) {
            CommandTree sub = subs.get(args[0].toLowerCase(Locale.ROOT));
            if (sub != null) {
                return sub.complete(source, Arrays.copyOfRange(args, 1, args.length));
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            subs.forEach((key, sub) -> {
                if (key.equals(sub.name) && key.startsWith(typed)
                        && (sub.permission == null || source.permitted(sub.permission))) {
                    out.add(key);
                }
            });
        }
        if (tab != null) {
            for (String s : tab.apply(new Ctx(source, args, name))) {
                if (s.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
