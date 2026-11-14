package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.util.List;
import java.util.Set;

/**
 * Which of the generate arguments one texture {@code kind} reads (#517, #520, #523). A kind that does not read an
 * argument refuses it in one line naming the kinds that do, instead of dropping it with an OK. KindArgsTest walks
 * every kind times every argument, so a new kind or argument fails there until it is declared here.
 */
record KindArgs(String kind, Set<String> reads) {
    /** The arguments a generated texture may read, besides kind itself. */
    static final List<String> ARGS = List.of("color", "contrast", "accent", "size", "seed");

    private static final Set<String> PATTERN = Set.of("color", "contrast", "size", "seed");
    private static final Set<String> PATTERN_ACCENT = Set.of("color", "contrast", "size", "seed", "accent");
    private static final Set<String> SHAPE = Set.of("color", "contrast");
    private static final Set<String> SHAPE_ACCENT = Set.of("color", "contrast", "accent");

    /** Every kind, in the order kind's description lists them. */
    static final List<KindArgs> KINDS = List.of(
            new KindArgs("noise", PATTERN), new KindArgs("ore", PATTERN_ACCENT),
            new KindArgs("planks", PATTERN), new KindArgs("bricks", PATTERN_ACCENT),
            new KindArgs("gem", SHAPE), new KindArgs("ingot", SHAPE), new KindArgs("orb", SHAPE),
            new KindArgs("sword", SHAPE_ACCENT), new KindArgs("pickaxe", SHAPE_ACCENT),
            new KindArgs("food", SHAPE_ACCENT));

    /** The one-line refusal for {@code arg} given to {@code kind}, which does not read it. */
    static String refusal(String arg, String kind) {
        List<String> readers = KINDS.stream().filter(k -> k.reads.contains(arg)).map(KindArgs::kind).toList();
        String list = readers.size() == 1 ? readers.getFirst()
                : String.join(", ", readers.subList(0, readers.size() - 1)) + " and " + readers.getLast();
        return arg + " is for kind " + list + "; " + kind + " does not use it, leave it out";
    }

    /** Refuses the first argument in {@link #ARGS} that {@code args} gives and {@code kind} does not read. */
    static void check(Args args, String kind) {
        KindArgs declared = KINDS.stream().filter(k -> k.kind.equals(kind)).findFirst()
                .orElseThrow(() -> new IllegalStateException("kind " + kind + " is not declared in KindArgs"));
        for (String arg : ARGS) {
            if (args.has(arg) && !declared.reads.contains(arg)) {
                throw new ToolException(refusal(arg, kind));
            }
        }
    }
}
