package io.github.imnotstable.msc.agent.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Block entity data through the server's own {@code /data} command (ADR 0042): Paper's API has no NBT for block
 * entities, so this is what the mod agents do with {@code saveWithoutMetadata} and {@code merge}, done the way the
 * game does it. Main thread only.
 */
final class DataCommands implements BlockNbt {
    /** Runs a console command line with full permissions, passing each line of feedback on. */
    @FunctionalInterface
    interface Console {
        boolean run(String line, Consumer<String> feedback);
    }

    private final Console console;

    DataCommands(Console console) {
        this.console = console;
    }

    @Override
    public String get(org.bukkit.World world, int x, int y, int z) {
        return get(world.getKey().asString(), x, y, z);
    }

    @Override
    public void merge(org.bukkit.World world, int x, int y, int z, String snbt) {
        merge(world.getKey().asString(), x, y, z, snbt);
    }

    /**
     * The block's data as SNBT from {@code /data get block}, or null when the command shows none. The SNBT keeps the
     * id and position, which the core drops.
     *
     * @param dimension the world's dimension id, such as {@code minecraft:overworld}
     */
    String get(String dimension, int x, int y, int z) {
        List<String> out = run(in(dimension) + "data get block " + x + " " + y + " " + z);
        return snbt(String.join("\n", out));
    }

    /** {@code /data merge block}; a merge that changes nothing is not an error, anything else the command says is. */
    void merge(String dimension, int x, int y, int z, String snbt) {
        List<String> out = run(in(dimension) + "data merge block " + x + " " + y + " " + z + " " + snbt);
        String said = String.join(" ", out);
        if (!said.contains("Modified block data") && !said.contains("Nothing changed")) {
            throw new IllegalArgumentException("data merge at " + x + "," + y + "," + z + " failed: " + said);
        }
    }

    /** The SNBT in {@code /data get block}'s answer ({@code X, Y, Z has the following block data: {...}}), or null. */
    static String snbt(String answer) {
        int open = answer.indexOf('{');
        return open < 0 ? null : answer.substring(open).strip();
    }

    private static String in(String dimension) {
        return "execute in " + dimension + " run ";
    }

    private List<String> run(String line) {
        List<String> out = new ArrayList<>();
        console.run(line, out::add);
        return out;
    }
}
