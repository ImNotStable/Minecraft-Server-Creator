package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The one rule for structure and snapshot files (#509). Readers also take the game's structure files (.nbt) and
 * Litematica's .litematic (ADR 0082), told apart by their content. structure_save writes a Sponge schematic and only under a name
 * ending in {@code .schem} (any case), so a name says what the file is. Readers (world_summary diff=, build_batch's
 * {@code file=}) go by the content, not the name: gzip data is a schematic, anything else must be UTF-8 text in the
 * text structure format; a file that is neither is refused in one line naming it and the formats.
 */
public final class StructureFile {
    /** The extension structure_save requires. */
    public static final String EXTENSION = ".schem";

    private StructureFile() {
    }

    /** Refuses a structure_save target whose name does not end in {@link #EXTENSION}; returns {@code file}. */
    public static Path checkSaveName(Path file, String what) {
        Path name = file.getFileName();
        if (name == null || !name.toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            throw new ToolException(what + " must end in " + EXTENSION + " (a Sponge schematic), such as "
                    + "structures/house" + EXTENSION + ", not " + file);
        }
        return file;
    }

    /** The structure in {@code file}, its lowest corner at {@code origin}. The file must exist. */
    public static BlockGrid load(Path file, String world, BlockPos origin) throws IOException {
        if (gzip(file)) {
            java.util.Map<String, Object> root = Schematic.read(file);
            String name = String.valueOf(file.getFileName());
            String kind = StructureFormats.kind(root);
            if (kind == null) {
                return Schematic.load(root, file, world, origin);
            }
            return switch (kind) {
                case "litematic" -> StructureFormats.litematic(root, name, world, origin);
                case "structure" -> StructureFormats.structure(root, name, world, origin);
                default -> throw new ToolException(name + " is an MCEdit .schematic (numeric ids from before 1.13); open "
                        + "it in a current WorldEdit and save it as .schem");
            };
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new ToolException(file + " is neither a Sponge schematic (as structure_save writes) nor a UTF-8 text"
                    + " structure; save a snapshot with structure_save");
        }
        return TextGrid.parse(text, world, origin);
    }

    private static boolean gzip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        }
    }
}
