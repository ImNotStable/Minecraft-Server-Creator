package io.github.imnotstable.msc.bench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The command line, read in one place (#609): {@link #COMMANDS} is the one table of the flags each command takes, and
 * {@link #FLAGS} gives each flag's arity. A flag no command knows, a flag the command does not take, a flag given twice
 * and a flag without its value are each refused naming the flag, so no flag is silently dropped or replaced.
 *
 * @param command    the command, or null when none was given
 * @param positional the arguments after the command that are not flags or their values
 * @param flags      each flag given, with its value ({@code ""} for a flag without one), a path's {@code ~} expanded
 */
record CommandLine(String command, List<String> positional, Map<String, String> flags) {

    /**
     * A flag.
     *
     * @param name  the flag, such as {@code --tasks}
     * @param value what its value is in the usage, or null for a flag that takes none
     * @param path  whether its value is a path, whose leading {@code ~} is the home folder
     * @param once  how to give it once, for the refusal of a flag given twice
     */
    record Flag(String name, String value, boolean path, String once) {

        boolean takesValue() {
            return value != null;
        }
    }

    /** Every flag, in the order the usage shows them. */
    static final Map<String, Flag> FLAGS = byName(
            new Flag("--tasks", "a,b", false, "list the tasks in one --tasks a,b"),
            new Flag("--modes", "m1,m2", false, "list the modes in one --modes m1,m2"),
            new Flag("--repeat", "N", false, "give it once"),
            new Flag("--model", "M", false, "give it once"),
            new Flag("--isolate", null, false, "give it once"),
            new Flag("--out", "DIR", true, "give it once"),
            new Flag("--tasks-dir", "DIR", true, "give it once"),
            new Flag("--config", "FILE", true, "give it once"));

    /** Each command with the flags it takes; a command reads no other flag. */
    static final Map<String, List<String>> COMMANDS = commands();

    CommandLine {
        positional = List.copyOf(positional);
        flags = Collections.unmodifiableMap(new HashMap<>(flags));
    }

    /** A flag as given, with its value, or null when the line ended before it. */
    private record Given(Flag flag, String value) {
    }

    private static Map<String, Flag> byName(Flag... flags) {
        Map<String, Flag> out = new LinkedHashMap<>();
        for (Flag flag : flags) {
            out.put(flag.name(), flag);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, List<String>> commands() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("list", List.of("--tasks-dir"));
        out.put("run", List.copyOf(FLAGS.keySet()));
        out.put("check", List.of("--tasks-dir", "--config"));
        out.put("report", List.of());
        return Collections.unmodifiableMap(out);
    }

    /**
     * Reads {@code args}. A flag's value is the argument after it, whatever it looks like, as before; every other
     * argument that starts with {@code --} must be a flag of {@link #FLAGS}.
     *
     * @throws IllegalArgumentException naming the first argument that is wrong
     */
    static CommandLine parse(String[] args, Path home) {
        List<String> positional = new ArrayList<>();
        List<Given> given = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            Flag flag = FLAGS.get(args[i]);
            if (flag != null) {
                String value = !flag.takesValue() ? "" : i + 1 < args.length ? args[++i] : null;
                given.add(new Given(flag, value));
            } else if (args[i].startsWith("--")) {
                throw new IllegalArgumentException("unknown option " + args[i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.isEmpty()) {
            return new CommandLine(null, List.of(), Map.of());
        }
        String command = positional.getFirst();
        List<String> takes = COMMANDS.get(command);
        if (takes == null) {
            throw new IllegalArgumentException("unknown command " + command + "; the commands are "
                    + String.join(", ", COMMANDS.keySet()));
        }
        Map<String, String> flags = new HashMap<>();
        for (Given entry : given) {
            Flag flag = entry.flag();
            if (!takes.contains(flag.name())) {
                throw new IllegalArgumentException(command + " does not take " + flag.name() + "; " + command
                        + (takes.isEmpty() ? " takes no options" : " takes only " + String.join(", ", takes)));
            }
            if (flags.containsKey(flag.name())) {
                throw new IllegalArgumentException(flag.name() + " is given twice; " + flag.once());
            }
            if (entry.value() == null) {
                throw new IllegalArgumentException(flag.name() + " needs a value");
            }
            flags.put(flag.name(), flag.path() ? Main.expandHome(entry.value(), home).toString() : entry.value());
        }
        return new CommandLine(command, positional.subList(1, positional.size()), flags);
    }

    /** The options of {@code command} as the usage shows them, such as {@code [--repeat N] [--isolate]}. */
    static String options(String command) {
        return COMMANDS.get(command).stream().map(FLAGS::get)
                .map(f -> "[" + f.name() + (f.takesValue() ? " " + f.value() : "") + "]")
                .collect(Collectors.joining(" "));
    }
}
