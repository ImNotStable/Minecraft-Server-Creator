package io.github.imnotstable.msc.core.world;

/**
 * Short block names for the model ({@code stone}), full ones for the agent ({@code minecraft:stone}). A name may end
 * in block entity data as SNBT, as {@code /setblock} takes it: {@code chest[facing=north]{Items:[...]}} (ADR 0042).
 */
public final class BlockNames {
    private BlockNames() {
    }

    /**
     * Add {@code minecraft:} when no namespace is given. Properties in brackets are kept; block entity data is checked
     * and written in the core's one spelling ({@link Snbt#blockData}), and left out when it holds nothing.
     */
    public static String full(String state) {
        String s = state.trim();
        int data = dataStart(s);
        String snbt = data < 0 ? null : Snbt.blockData(s.substring(data));
        s = data < 0 ? s : s.substring(0, data).trim();
        int bracket = s.indexOf('[');
        String id = bracket < 0 ? s : s.substring(0, bracket);
        String named = id.contains(":") ? s : "minecraft:" + s;
        return snbt == null ? named : named + snbt;
    }

    /** Drop the {@code minecraft:} namespace; other namespaces stay. */
    public static String brief(String state) {
        return state.startsWith("minecraft:") ? state.substring(10) : state;
    }

    /** The block id without properties or data. */
    public static String id(String state) {
        String s = withoutData(state);
        int bracket = s.indexOf('[');
        return bracket < 0 ? s : s.substring(0, bracket);
    }

    /** The state without its block entity data. */
    public static String withoutData(String state) {
        int data = dataStart(state);
        return data < 0 ? state : state.substring(0, data);
    }

    /** The block entity data, from its opening brace, or null when the name has none. */
    public static String data(String state) {
        int data = dataStart(state);
        return data < 0 ? null : state.substring(data);
    }

    /** Where the data starts: the first opening brace outside the properties' brackets, or -1. */
    static int dataStart(String state) {
        int depth = 0;
        for (int i = 0; i < state.length(); i++) {
            char c = state.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '{' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isAir(String state) {
        String id = brief(id(state));
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }
}
