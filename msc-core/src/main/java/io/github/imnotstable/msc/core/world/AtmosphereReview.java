package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Whether a place feels alive, as far as its blocks tell (ADR 0114): blocks that make a sound or move on their own
 * (fire crackles, flowing water runs, torches smoke, spore blossoms drift), and whether its lights are more than one
 * kind. Sky, weather, time, biome and plugin loops are not in the blocks; the atmosphere guide covers them. One pass
 * over the grid, one short line.
 */
final class AtmosphereReview {
    /** Built blocks from which a still, silent build is a failure: the bar no plants also uses. */
    static final int MIN_BUILT = 300;
    /** Lights from which all of one kind is a failure: a few lanterns on a hut are fine. */
    static final int MIN_LIGHTS = 8;

    /** Blocks the game plays a sound at by themselves, when lit or flowing where that applies. */
    private static final Set<String> SOUND = Set.of("campfire", "soul_campfire", "fire", "soul_fire", "lava",
            "bubble_column", "furnace", "smoker", "blast_furnace", "nether_portal", "firefly_bush", "short_dry_grass",
            "tall_dry_grass");
    /** Blocks the game spawns particles at by themselves, when lit where that applies. */
    private static final Set<String> MOTION = Set.of("torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "redstone_torch", "redstone_wall_torch", "campfire", "soul_campfire", "fire", "soul_fire", "lava",
            "end_rod", "spore_blossom", "crying_obsidian", "firefly_bush", "cherry_leaves", "mycelium", "nether_portal",
            "furnace", "smoker", "blast_furnace", "ender_chest", "bubble_column", "respawn_anchor");
    /** Blocks that are lit unless their state says lit=false; every other lit-able block needs lit=true. */
    private static final Set<String> LIT_BY_DEFAULT = Set.of("campfire", "soul_campfire", "redstone_torch",
            "redstone_wall_torch");

    private AtmosphereReview() {
    }

    /** The line for the review and the fixes it adds. */
    record Result(String line, List<String> fixes) {
    }

    /**
     * {@code built}: the build's block counts by brief name (ground left out); {@code isLight}: which of those names
     * give light.
     */
    static Result review(BlockGrid grid, Map<String, Integer> built, Predicate<String> isLight) {
        int[] uses = new int[grid.palette().size()];
        for (int b : grid.blocks()) {
            if (b >= 0) {
                uses[b]++;
            }
        }
        int sound = 0;
        int motion = 0;
        for (int i = 0; i < uses.length; i++) {
            if (uses[i] == 0) {
                continue;
            }
            String state = BlockNames.brief(grid.palette().get(i));
            String id = BlockNames.id(state);
            String kind = id.endsWith("candle_cake") ? "candle_cake" : id.endsWith("candle") ? "candle" : id;
            boolean active = active(kind, BlockNames.withoutData(state));
            // Still water is silent; water with a level is flowing and runs (a stream, a fountain, a fall).
            boolean flowing = kind.equals("water") && state.contains("level=") && !state.contains("level=0]")
                    && !state.contains("level=0,");
            sound += active && SOUND.contains(kind) || flowing ? uses[i] : 0;
            motion += active && (MOTION.contains(kind) || kind.startsWith("candle")) ? uses[i] : 0;
        }
        Set<String> kinds = new TreeSet<>();
        int lights = 0;
        for (Map.Entry<String, Integer> e : built.entrySet()) {
            if (isLight.test(e.getKey())) {
                kinds.add(e.getKey().replace("wall_", ""));
                lights += e.getValue();
            }
        }
        int total = built.values().stream().mapToInt(Integer::intValue).sum();
        List<String> fixes = new ArrayList<>();
        if (total >= MIN_BUILT && sound + motion == 0) {
            fixes.add("still: no block here makes a sound or moves (fire, flowing water, torch smoke, candles, spore "
                    + "blossoms), so it feels dead; give it what its story would sound and move like, where that "
                    + "happens (msc_status guide=atmosphere)");
        }
        if (lights >= MIN_LIGHTS && kinds.size() == 1) {
            fixes.add("one light: all " + lights + " lights are " + kinds.iterator().next() + "; light by use and "
                    + "mood (fire where people cook or warm up, candles on tables, a brighter focal point, a cooler "
                    + "or dimmer edge), not one fixture everywhere");
        }
        String line = "atmosphere: " + sound + " sounding and " + motion + " moving blocks, " + kinds.size()
                + (kinds.size() == 1 ? " light kind" : " light kinds");
        return new Result(line, fixes);
    }

    /** A candle, campfire or furnace sounds and moves only when lit; water only when flowing. */
    private static boolean active(String kind, String state) {
        if (kind.equals("respawn_anchor")) {
            return state.contains("[") && !state.contains("charges=0");
        }
        boolean litAble = kind.startsWith("candle") || kind.endsWith("campfire") || kind.endsWith("furnace")
                || kind.equals("smoker") || kind.startsWith("redstone_");
        if (!litAble) {
            return true;
        }
        return LIT_BY_DEFAULT.contains(kind) ? !state.contains("lit=false") : state.contains("lit=true");
    }
}
