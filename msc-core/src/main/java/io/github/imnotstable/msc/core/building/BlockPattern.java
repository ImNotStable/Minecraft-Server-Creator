package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BlockNames;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One block or a weighted mix such as {@code grass_block:78,moss_block:10,coarse_dirt:12}. A weight
 * is the number after the last colon; it defaults to 1. Which block a position gets depends only on
 * the position and the seed, so a dry run, a repeat and an undo-and-redo all give the same result. With
 * {@code clump > 0} the mix comes in patches about that many blocks across, with wavy edges, instead of block by block
 * (ADR 0075): ground and wall weathering that reads as patches, not static. A ramp {@code a>b>c} (ADR 0077) is a
 * gradient: its stages, each a block or a mix, follow one another along an axis of the step's box, and neighbouring
 * stages blend over a ragged seam, so a wall darkens toward its base without skipping a shade.
 */
public record BlockPattern(List<String> states, int[] cumulative, long seed, int clump, Ramp ramp) {
    private static final Pattern WEIGHT = Pattern.compile("^(.*\\S):(\\d+)$");

    /** A gradient's stages along {@code axis} (0 x, 1 y, 2 z), first at {@code from}, last at {@code to}. */
    public record Ramp(List<BlockPattern> stages, int axis, int from, int to) {
        public Ramp {
            stages = List.copyOf(stages);
        }
    }

    public BlockPattern {
        states = List.copyOf(states);
    }

    public BlockPattern(List<String> states, int[] cumulative, long seed, int clump) {
        this(states, cumulative, seed, clump, null);
    }

    /** Every block this pattern can give, a ramp's stages included. */
    public List<String> allStates() {
        if (ramp == null) {
            return states;
        }
        List<String> out = new ArrayList<>();
        for (BlockPattern p : ramp.stages()) {
            out.addAll(p.allStates());
        }
        return out;
    }

    /** Whether this is a ramp still waiting for the box it spans. */
    public boolean needsSpan() {
        return ramp != null && ramp.from() == Integer.MIN_VALUE;
    }

    /** A ramp spread over {@code box} along {@code axis} ("x", "y", "z"; "-y" runs top to bottom); others as they are. */
    public BlockPattern spanning(io.github.imnotstable.msc.protocol.world.Region box, String axis) {
        if (ramp == null) {
            return this;
        }
        boolean back = axis.startsWith("-");
        int a = switch (back ? axis.substring(1) : axis) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> throw new ToolException("grade is x, y, z, -x, -y or -z, got " + axis);
        };
        int lo = a == 0 ? box.min().x() : a == 1 ? box.min().y() : box.min().z();
        int hi = a == 0 ? box.max().x() : a == 1 ? box.max().y() : box.max().z();
        return new BlockPattern(states, cumulative, seed, clump,
                new Ramp(ramp.stages(), a, back ? hi : lo, back ? lo : hi));
    }

    public static BlockPattern single(String state) {
        return new BlockPattern(List.of(BlockNames.full(state)), new int[] {1}, 0, 0);
    }

    public static BlockPattern parse(String text) {
        return parse(text, 0);
    }

    /** Parse a pattern. Commas inside {@code [...]} belong to block properties, not to the list. */
    public static BlockPattern parse(String text, long seed) {
        if (text == null || text.isBlank()) {
            throw new ToolException("block is empty");
        }
        List<String> parts = split(text.trim(), '>');
        if (parts.size() > 1) {
            List<BlockPattern> stages = new ArrayList<>();
            for (String part : parts) {
                stages.add(parse(part, seed));
            }
            BlockPattern first = stages.get(0);
            return new BlockPattern(first.states(), first.cumulative(), seed, 0,
                    new Ramp(stages, 1, Integer.MIN_VALUE, Integer.MIN_VALUE));
        }
        List<String> states = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (String entry : splitTopLevel(text.trim())) {
            String e = entry.trim();
            if (e.isEmpty()) {
                throw new ToolException("empty entry in block pattern \"" + text + "\"");
            }
            int weight = 1;
            Matcher m = WEIGHT.matcher(e);
            // "minecraft:stone" has no digits after the colon, so it is a namespace, not a weight.
            if (m.matches()) {
                try {
                    weight = Integer.parseInt(m.group(2));
                } catch (NumberFormatException ex) {
                    throw new ToolException("weight too large in \"" + e + "\"");
                }
                e = m.group(1).trim();
            }
            if (weight <= 0) {
                continue;
            }
            states.add(BlockNames.full(e));
            weights.add(weight);
        }
        if (states.isEmpty()) {
            throw new ToolException("block pattern \"" + text + "\" has no block with a weight above 0");
        }
        int[] cumulative = new int[weights.size()];
        long sum = 0;
        for (int i = 0; i < cumulative.length; i++) {
            sum += weights.get(i);
            if (sum > Integer.MAX_VALUE) {
                throw new ToolException("weights in \"" + text + "\" add up to too much");
            }
            cumulative[i] = (int) sum;
        }
        return new BlockPattern(states, cumulative, seed, 0);
    }

    /** Whether {@code text} is more than a single block, so it needs this class rather than a plain fill. */
    public static boolean isMix(String text) {
        return splitTopLevel(text.trim()).size() > 1 || split(text.trim(), '>').size() > 1 || WEIGHT.matcher(text.trim()).matches();
    }

    /** Splits at commas outside {@code [...]} and block entity data {@code {...}} (ADR 0042). */
    static List<String> splitTopLevel(String text) {
        return split(text, ',');
    }

    /** Splits at {@code at} outside {@code [...]} and block entity data. */
    static List<String> split(String text, char at) {
        List<String> out = new ArrayList<>();
        int start = 0;
        Nesting n = new Nesting();
        for (int i = 0; i < text.length(); i++) {
            if (n.top(text.charAt(i)) && text.charAt(i) == at) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    /**
     * Follows a block name character by character: {@link #top} says whether a character is outside brackets, braces
     * and the quoted strings in block entity data, which may hold any character.
     */
    public static final class Nesting {
        private int depth;
        private char quote;
        private boolean escaped;

        public boolean top(char c) {
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                return false;
            }
            if (c == '[' || c == '{') {
                depth++;
                return false;
            }
            if (c == ']' || c == '}') {
                depth--;
                return false;
            }
            if ((c == '"' || c == '\'') && depth > 0) {
                quote = c;
            }
            return depth == 0;
        }
    }

    public BlockPattern withSeed(long newSeed) {
        return new BlockPattern(states, cumulative, newSeed, clump, ramp == null ? null
                : new Ramp(ramp.stages().stream().map(p -> p.withSeed(newSeed)).toList(), ramp.axis(), ramp.from(), ramp.to()));
    }

    /** The same mix in patches about {@code size} blocks across; 0 picks block by block. */
    public BlockPattern withClump(int size) {
        return new BlockPattern(states, cumulative, seed, size, ramp == null ? null
                : new Ramp(ramp.stages().stream().map(p -> p.withClump(size)).toList(), ramp.axis(), ramp.from(), ramp.to()));
    }

    /** The block for one position. */
    public String at(int x, int y, int z) {
        if (ramp != null) {
            return stage(x, y, z).at(x, y, z);
        }
        if (states.size() == 1) {
            return states.get(0);
        }
        long h = clump > 0 ? patch(x, y, z) : mix(seed ^ mix(x * 0x9E3779B97F4A7C15L ^ mix(y * 0xC2B2AE3D27D4EB4FL ^ mix(z))));
        int total = cumulative[cumulative.length - 1];
        int roll = (int) Long.remainderUnsigned(h, total);
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return states.get(i);
            }
        }
        return states.get(states.size() - 1);
    }

    /**
     * The ramp's stage at a position: where it lies between the ramp's ends, moved up to most of a stage either way by a
     * hash (or smooth noise with clump), so two stages meet over a ragged seam and only neighbouring stages mix.
     */
    private BlockPattern stage(int x, int y, int z) {
        int n = ramp.stages().size();
        int c = ramp.axis() == 0 ? x : ramp.axis() == 1 ? y : z;
        double t = ramp.from() == ramp.to() || ramp.from() == Integer.MIN_VALUE ? 0
                : (double) (c - ramp.from()) / (ramp.to() - ramp.from());
        double jitter = clump > 0 ? 0.5 * Noise.gradient(seed + 9, (x + z) / (double) clump, (y - z) / (double) clump)
                : Noise.unit(mix(seed ^ mix(x * 0x9E3779B97F4A7C15L ^ mix(y * 0xC2B2AE3D27D4EB4FL ^ mix(z + 77L))))) - 0.5;
        int i = (int) Math.floor(Math.clamp(t, 0, 1) * n + 0.8 * jitter);
        return ramp.stages().get(Math.clamp(i, 0, n - 1));
    }

    /**
     * One roll per patch: the position, pushed about by smooth noise so patch edges wave, picks a cell {@code clump}
     * blocks across, and the cell's hash picks the block. Every cell is one roll, so the weights still hold.
     */
    private long patch(int x, int y, int z) {
        double c = clump;
        double push = 0.7 * c;
        double wx = x + push * Noise.gradient(seed + 1, z / c, y / c);
        double wy = y + push * Noise.gradient(seed + 2, x / c, z / c);
        double wz = z + push * Noise.gradient(seed + 3, x / c, y / c);
        long cx = (long) Math.floor(wx / c);
        long cy = (long) Math.floor(wy / c);
        long cz = (long) Math.floor(wz / c);
        return mix(seed ^ mix(cx * 0x9E3779B97F4A7C15L ^ mix(cy * 0xC2B2AE3D27D4EB4FL ^ mix(cz))));
    }

    /** SplitMix64 finaliser: a fast, well-spread hash of one long. */
    static long mix(long v) {
        long z = v + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockPattern p && p.states.equals(states) && java.util.Arrays.equals(p.cumulative, cumulative)
                && p.seed == seed && p.clump == clump && java.util.Objects.equals(p.ramp, ramp);
    }

    @Override
    public int hashCode() {
        return states.hashCode() * 31 + java.util.Arrays.hashCode(cumulative);
    }

    @Override
    public String toString() {
        if (ramp != null) {
            return String.join(">", ramp.stages().stream().map(BlockPattern::toString).toList());
        }
        StringBuilder out = new StringBuilder();
        int previous = 0;
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(BlockNames.brief(states.get(i)));
            if (states.size() > 1) {
                out.append(':').append(cumulative[i] - previous);
            }
            previous = cumulative[i];
        }
        return out.toString();
    }
}
