package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * An argument that only some ways of a tool read, and the one-line refusal for a call that gives it to another way.
 * A tool that picks a way (texture draws, generates or recolors; pack builds or only checks) refuses such an argument
 * instead of dropping it with an OK (#517).
 */
record WayArgs(String arg, Set<String> readers, String refusal) {
    /** The texture tool. Ways: rows, kind or copy; animate; pack (the texture goes into the pack, not a .png path). */
    static final List<WayArgs> TEXTURE = List.of(
            new WayArgs("palette", Set.of("rows"), "palette is for rows; kind and copy take color"),
            new WayArgs("color", Set.of("kind", "copy"), "color is for kind and copy; rows take their colors from palette"),
            new WayArgs("accent", Set.of("kind"), "accent is for kind; leave it out"),
            new WayArgs("size", Set.of("kind"), "size is for kind; rows set the size by their length"),
            new WayArgs("seed", Set.of("kind"), "seed is for kind; leave it out"),
            new WayArgs("contrast", Set.of("kind"), "contrast is for kind; leave it out"),
            new WayArgs("frames", Set.of("animate"), "frames is for animate; leave it out or add animate"),
            new WayArgs("frametime", Set.of("animate"), "frametime is for animate; leave it out or add animate"),
            new WayArgs("model", Set.of("pack"),
                    "model is written only with rows, kind or copy into the pack; leave it out"));

    /** The pack tool. Ways: check (only list problems) or build. */
    static final List<WayArgs> PACK = List.of(
            new WayArgs("description", Set.of("build"), "description is for a build; leave it out with check=true"),
            new WayArgs("file", Set.of("build"), "file is for a build; leave it out with check=true"),
            new WayArgs("apply", Set.of("build"), "apply is for a build; leave it out with check=true"),
            new WayArgs("required", Set.of("build"), "required is for a build; leave it out with check=true"));

    /** Refuses the first argument in {@code table} that {@code args} gives and none of {@code ways} reads. */
    static void check(Args args, Set<String> ways, List<WayArgs> table) {
        for (WayArgs w : table) {
            if (args.has(w.arg) && Collections.disjoint(w.readers, ways)) {
                throw new ToolException(w.refusal);
            }
        }
    }
}
