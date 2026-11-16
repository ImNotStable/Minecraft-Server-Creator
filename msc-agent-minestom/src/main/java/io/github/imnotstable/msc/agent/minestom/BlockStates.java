package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.BlockNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minestom.server.instance.block.Block;

/** Vanilla block state strings to Minestom blocks and back. */
final class BlockStates {
    private BlockStates() {
    }

    /**
     * Parses {@code minecraft:oak_stairs[facing=north]}; the namespace may be left out. Properties not
     * named keep their default values, as in vanilla.
     *
     * @throws IllegalArgumentException for an unknown block (with close ids), or a bad property or
     *     value on a real block (saying which, and what the block takes)
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
            throw new IllegalArgumentException(explain(state));
        }
        return block;
    }

    /** The full vanilla state string, for example {@code minecraft:oak_stairs[facing=north,half=bottom,...]}. */
    static String format(Block block) {
        return block.state();
    }

    /** Why {@code state} failed: bad properties on a real block, or an unknown id with close names. */
    private static String explain(String state) {
        String s = state.strip();
        int bracket = s.indexOf('[');
        String id = (bracket < 0 ? s : s.substring(0, bracket)).strip();
        String key = id.toLowerCase(Locale.ROOT);
        Block base;
        try {
            base = Block.fromKey(key.contains(":") ? key : "minecraft:" + key);
        } catch (RuntimeException e) {
            base = null;
        }
        if (base == null) {
            String bare = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
            List<String> ids = Block.values().stream().map(b -> b.key().value()).toList();
            List<String> close = BlockNames.suggest(bare, ids);
            return "unknown block state: " + state
                    + (close.isEmpty() ? "" : "; did you mean " + String.join(", ", close) + "?");
        }
        return "bad block properties in " + state + ": " + propertyProblems(id, base,
                bracket < 0 ? "" : s.substring(bracket));
    }

    /** What is wrong in {@code brackets} (such as {@code [facing=up]}) for {@code block}. */
    private static String propertyProblems(String id, Block block, String brackets) {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        block.defaultState().properties().keySet().forEach(name -> allowed.put(name, new LinkedHashSet<>()));
        for (Block possible : block.possibleStates()) {
            possible.properties().forEach((name, value) -> allowed.computeIfAbsent(name, n -> new LinkedHashSet<>())
                    .add(value));
        }
        if (allowed.isEmpty()) {
            return id + " has no properties";
        }
        String takes = id + " takes " + String.join(", ", allowed.keySet());
        if (!brackets.endsWith("]")) {
            return "expected [name=value,...]; " + takes;
        }
        List<String> problems = new ArrayList<>();
        String inner = brackets.substring(1, brackets.length() - 1);
        for (String entry : inner.split(",", -1)) {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                problems.add("expected name=value, got '" + entry.strip() + "'");
                continue;
            }
            String name = entry.substring(0, eq).strip();
            String value = entry.substring(eq + 1).strip();
            Set<String> values = allowed.get(name);
            if (values == null) {
                problems.add("unknown property " + name);
            } else if (!values.contains(value)) {
                problems.add(name + "=" + value + " is not one of " + String.join(", ", values));
            }
        }
        if (problems.isEmpty()) {
            return takes;
        }
        return String.join("; ", problems) + "; " + takes;
    }
}
