package io.github.imnotstable.msc.protocol.op;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a {@link Ops#WORLD_WRITE} that failed after changing blocks (a timeout, a shutdown, an error mid-way) names
 * the undo id of the blocks it did change. The agent appends {@link #note} to its error message; the core reads the
 * id back with {@link #editId} and undoes it with {@link Ops#WORLD_UNDO}, so a failed write never leaves changed
 * blocks that no undo id covers.
 */
public final class PartialEdit {
    private static final String START = " (blocks changed before the failure are undoable as edit ";
    private static final Pattern ID = Pattern.compile(Pattern.quote(START) + "([^\\s)]+)\\)$");

    private PartialEdit() {
    }

    /** {@code message} with the undo id of the blocks changed so far appended. */
    public static String note(String message, String editId) {
        return message + START + editId + ")";
    }

    /** The undo id {@link #note} appended to an error message, if any. */
    public static Optional<String> editId(String message) {
        Matcher m = ID.matcher(message == null ? "" : message);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
