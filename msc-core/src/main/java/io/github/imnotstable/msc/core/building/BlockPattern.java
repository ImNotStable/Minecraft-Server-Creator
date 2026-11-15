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
 * the position and the seed, so a dry run, a repeat and an undo-and-redo all give the same result.
 */
public record BlockPattern(List<String> states, int[] cumulative, long seed) {
    private static final Pattern WEIGHT = Pattern.compile("^(.*\\S):(\\d+)$");

    public BlockPattern {
        states = List.copyOf(states);
    }

    public static BlockPattern single(String state) {
        return new BlockPattern(List.of(BlockNames.full(state)), new int[] {1}, 0);
    }

    public static BlockPattern parse(String text) {
        return parse(text, 0);
    }

    /** Parse a pattern. Commas inside {@code [...]} belong to block properties, not to the list. */
    public static BlockPattern parse(String text, long seed) {
        if (text == null || text.isBlank()) {
            throw new ToolException("block is empty");
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
        return new BlockPattern(states, cumulative, seed);
    }

    /** Whether {@code text} is more than a single block, so it needs this class rather than a plain fill. */
    public static boolean isMix(String text) {
        return splitTopLevel(text.trim()).size() > 1 || WEIGHT.matcher(text.trim()).matches();
    }

    /** Splits at commas outside {@code [...]} and block entity data {@code {...}} (ADR 0042). */
    static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        Nesting n = new Nesting();
        for (int i = 0; i < text.length(); i++) {
            if (n.top(text.charAt(i)) && text.charAt(i) == ',') {
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
        return new BlockPattern(states, cumulative, newSeed);
    }

    /** The block for one position. */
    public String at(int x, int y, int z) {
        if (states.size() == 1) {
            return states.get(0);
        }
        long h = mix(seed ^ mix(x * 0x9E3779B97F4A7C15L ^ mix(y * 0xC2B2AE3D27D4EB4FL ^ mix(z))));
        int total = cumulative[cumulative.length - 1];
        int roll = (int) Long.remainderUnsigned(h, total);
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return states.get(i);
            }
        }
        return states.get(states.size() - 1);
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
                && p.seed == seed;
    }

    @Override
    public int hashCode() {
        return states.hashCode() * 31 + java.util.Arrays.hashCode(cumulative);
    }

    @Override
    public String toString() {
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
