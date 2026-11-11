package io.github.imnotstable.msc.agent.fabric;

/**
 * World names in Paper's style, so the core and the AI see the same names on both platforms: the
 * overworld is the server's {@code level-name} (usually {@code world}), the vanilla nether and end
 * add {@code _nether} and {@code _the_end}, and any other dimension is named by its id
 * ({@code mymod:mining}). The dimension id is accepted as well, so {@code minecraft:the_nether}
 * finds the nether.
 */
final class LevelNames {
    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    static final String END = "minecraft:the_end";

    private LevelNames() {
    }

    /** The name the core sees for a dimension id. */
    static String name(String levelName, String dimensionId) {
        return switch (dimensionId) {
            case OVERWORLD -> levelName;
            case NETHER -> levelName + "_nether";
            case END -> levelName + "_the_end";
            default -> dimensionId;
        };
    }

    /** Does {@code requested} name this dimension, by its Paper-style name or by its id? */
    static boolean matches(String requested, String levelName, String dimensionId) {
        return requested.equals(name(levelName, dimensionId)) || requested.equals(dimensionId);
    }
}
