package io.github.imnotstable.msc.agent.minestom;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minestom.server.instance.block.Block;

/** Vanilla block state strings to Minestom blocks and back, plus the {@code onlyReplace} filter. */
final class BlockStates {
    private BlockStates() {
    }

    /**
     * Parses {@code minecraft:oak_stairs[facing=north]}; the namespace may be left out. Properties not
     * named keep their default values, as in vanilla.
     *
     * @throws IllegalArgumentException for an unknown block or property
     */
    static Block parse(String state) {
        String s = state.strip();
        int bracket = s.indexOf('[');
        String id = bracket < 0 ? s : s.substring(0, bracket);
        if (!id.contains(":")) {
            s = "minecraft:" + s;
        }
        Block block;
        try {
            block = Block.fromState(s);
        } catch (RuntimeException e) {
            block = null;
        }
        if (block == null) {
            throw new IllegalArgumentException("unknown block state: " + state);
        }
        return block;
    }

    /** The full vanilla state string, for example {@code minecraft:oak_stairs[facing=north,half=bottom,...]}. */
    static String format(Block block) {
        return block.state();
    }

    /**
     * Parses an {@code onlyReplace} entry. A state without properties (for example
     * {@code minecraft:stone}) matches every state of that block; named properties must match.
     */
    static Filter filter(String state) {
        Block block = parse(state);
        Map<String, String> named = new LinkedHashMap<>();
        int bracket = state.indexOf('[');
        if (bracket >= 0 && state.strip().endsWith("]")) {
            String inner = state.strip();
            inner = inner.substring(bracket + 1, inner.length() - 1);
            for (String pair : inner.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    named.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
                }
            }
        }
        return new Filter(block.id(), Map.copyOf(named));
    }

    /** A block id plus the properties the filter named. */
    record Filter(int blockId, Map<String, String> properties) {
        boolean matches(Block block) {
            if (block.id() != blockId) {
                return false;
            }
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!e.getValue().equals(block.getProperty(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }
}
