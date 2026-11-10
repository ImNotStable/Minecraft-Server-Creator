package io.github.imnotstable.msc.agent.paper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Recent edits by id, oldest dropped first once the total number of remembered blocks passes
 * {@code maxBlocks}. Ids carry a random prefix per server run so an id from an earlier run never
 * undoes the wrong edit.
 */
final class EditHistory {
    private final Map<String, UndoRecord> edits = new LinkedHashMap<>();
    private final long maxBlocks;
    private final String prefix;
    private long blocks;
    private long next = 1;

    EditHistory(long maxBlocks) {
        this.maxBlocks = maxBlocks;
        this.prefix = Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36, 36 * 36 * 36 * 36), 36);
    }

    /** Remember an edit and return its id. Edits larger than the whole budget get an id but no undo. */
    synchronized String add(UndoRecord record) {
        String id = prefix + "-" + next++;
        if (record.size() > maxBlocks) {
            return id;
        }
        edits.put(id, record);
        blocks += record.size();
        Iterator<UndoRecord> oldest = edits.values().iterator();
        while (blocks > maxBlocks && oldest.hasNext()) {
            blocks -= oldest.next().size();
            oldest.remove();
        }
        return id;
    }

    /** Remove and return an edit, so it can be undone once. */
    synchronized Optional<UndoRecord> take(String id) {
        UndoRecord record = edits.remove(id);
        if (record != null) {
            blocks -= record.size();
        }
        return Optional.ofNullable(record);
    }

    synchronized long rememberedBlocks() {
        return blocks;
    }
}
