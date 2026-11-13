package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The feedback lines of one command, kept while they fit in {@code maxChars} (each line counts one more): the line
 * that crosses it is cut with {@link Limits#cut}, later lines are only counted, and {@link #lines} ends with
 * {@link Ops.CommandResult#cutLine}. So no output, however long, makes an answer over the frame limit (#341).
 */
final class CommandOutput implements Consumer<String> {
    /** Room kept free for the cut line. */
    private static final int CUT_LINE_ROOM = 128;

    private final long budget;
    private final List<String> lines = new ArrayList<>();
    private long used;
    private boolean full;
    private long leftOut;

    CommandOutput(int maxChars) {
        this.budget = maxChars - CUT_LINE_ROOM;
    }

    @Override
    public synchronized void accept(String line) {
        String text = line == null ? "" : line;
        if (full) {
            leftOut++;
            return;
        }
        long room = budget - used - 1;
        if (text.length() <= room) {
            lines.add(text);
            used += text.length() + 1;
            return;
        }
        full = true;
        if (room >= 64) {
            lines.add(Limits.cut(text, (int) room));
            used = budget;
        } else {
            leftOut++;
        }
    }

    synchronized List<String> lines() {
        if (leftOut == 0) {
            return List.copyOf(lines);
        }
        List<String> out = new ArrayList<>(lines);
        out.add(Ops.CommandResult.cutLine(leftOut));
        return List.copyOf(out);
    }
}
