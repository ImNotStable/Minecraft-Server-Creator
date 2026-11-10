package io.github.imnotstable.msc.core.world;

/** Short block names for the model ({@code stone}), full ones for the agent ({@code minecraft:stone}). */
public final class BlockNames {
    private BlockNames() {
    }

    /** Add {@code minecraft:} when no namespace is given. Properties in brackets are kept. */
    public static String full(String state) {
        String s = state.trim();
        int bracket = s.indexOf('[');
        String id = bracket < 0 ? s : s.substring(0, bracket);
        return id.contains(":") ? s : "minecraft:" + s;
    }

    /** Drop the {@code minecraft:} namespace; other namespaces stay. */
    public static String brief(String state) {
        return state.startsWith("minecraft:") ? state.substring(10) : state;
    }

    /** The block id without properties. */
    public static String id(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    public static boolean isAir(String state) {
        String id = brief(id(state));
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }
}
