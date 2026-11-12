package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.protocol.op.Ops;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

/**
 * Sign text from a sign block's NBT: {@code front_text} and {@code back_text}, each with four {@code messages}.
 * A message is a text component stored as NBT: a plain string, or a compound with {@code text} and {@code extra}.
 */
final class SignNbt {
    private SignNbt() {
    }

    /** The sign's text, or null when the block has no NBT or both sides are blank. */
    static Ops.SignText read(int x, int y, int z, CompoundBinaryTag nbt) {
        if (nbt == null) {
            return null;
        }
        return Ops.SignText.of(x, y, z, side(nbt.get("front_text")), side(nbt.get("back_text")));
    }

    private static List<String> side(BinaryTag tag) {
        if (!(tag instanceof CompoundBinaryTag side)) {
            return null;
        }
        List<String> lines = new ArrayList<>(4);
        for (BinaryTag message : side.getList("messages")) {
            StringBuilder text = new StringBuilder();
            plain(message, text);
            lines.add(text.toString());
        }
        return lines;
    }

    private static void plain(BinaryTag tag, StringBuilder out) {
        switch (tag) {
            case StringBinaryTag s -> out.append(s.value());
            case ListBinaryTag list -> list.forEach(t -> plain(t, out));
            case CompoundBinaryTag c -> {
                BinaryTag text = c.get("text");
                if (text instanceof StringBinaryTag s) {
                    out.append(s.value());
                }
                BinaryTag extra = c.get("extra");
                if (extra != null) {
                    plain(extra, out);
                }
            }
            default -> {
            }
        }
    }
}
