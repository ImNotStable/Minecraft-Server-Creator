package io.github.imnotstable.msc.agent.common;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Recent edits by id, oldest dropped first once the total number of remembered blocks passes
 * {@code maxBlocks}. Ids carry a random prefix per server run so an id from an earlier run never
 * undoes the wrong edit.
 *
 * @param <S> the platform's block state type
 */
public final class EditHistory<S> {
    private final Map<String, UndoRecord<S>> edits = new LinkedHashMap<>();
    private final long maxBlocks;
    private final String prefix;
    private long blocks;
    private long next = 1;

    public EditHistory(long maxBlocks) {
        this.maxBlocks = maxBlocks;
        this.prefix = Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36, 36 * 36 * 36 * 36), 36);
    }

    /** Remember an edit and return its id. Edits larger than the whole budget get an id but no undo. */
    public synchronized String add(UndoRecord<S> record) {
        String id = prefix + "-" + next++;
        if (record.size() > maxBlocks) {
            return id;
        }
        keep(id, record);
        return id;
    }

    /**
     * Put back a record {@link #take} returned, under its old id, when its undo failed, so that a
     * retry can undo the rest. Re-running a half-applied record is safe: blocks already restored
     * are skipped.
     */
    public synchronized void putBack(String id, UndoRecord<S> record) {
        if (record.size() <= maxBlocks && !edits.containsKey(id)) {
            keep(id, record);
        }
    }

    private void keep(String id, UndoRecord<S> record) {
        edits.put(id, record);
        blocks += record.size();
        Iterator<UndoRecord<S>> oldest = edits.values().iterator();
        while (blocks > maxBlocks && oldest.hasNext()) {
            blocks -= oldest.next().size();
            oldest.remove();
        }
    }

    /** Remove and return an edit, so it can be undone once. */
    public synchronized Optional<UndoRecord<S>> take(String id) {
        UndoRecord<S> record = edits.remove(id);
        if (record != null) {
            blocks -= record.size();
        }
        return Optional.ofNullable(record);
    }

    public synchronized long rememberedBlocks() {
        return blocks;
    }
}
