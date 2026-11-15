package io.github.imnotstable.msc.agent.common;

/**
 * A palette entry split into its block state and the block entity data that may follow it as SNBT, as
 * {@code /setblock} takes it: {@code minecraft:chest[facing=north]{Items:[...]}} (ADR 0042).
 *
 * @param state the block state, such as {@code minecraft:chest[facing=north]}
 * @param snbt  the data from its opening brace on, or null when there is none
 */
public record BlockData(String state, String snbt) {
    /** Splits at the first opening brace outside the state's {@code [...]}. */
    public static BlockData split(String entry) {
        int depth = 0;
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '{' && depth == 0) {
                return new BlockData(entry.substring(0, i).strip(), entry.substring(i).strip());
            }
        }
        return new BlockData(entry, null);
    }

    /** The palette entry: the state, then the data when there is some. */
    public static String join(String state, String snbt) {
        return snbt == null ? state : state + snbt;
    }
}
