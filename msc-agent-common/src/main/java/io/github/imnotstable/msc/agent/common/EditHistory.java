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
        edits.put(id, record);
        blocks += record.size();
        Iterator<UndoRecord<S>> oldest = edits.values().iterator();
        while (blocks > maxBlocks && oldest.hasNext()) {
            blocks -= oldest.next().size();
            oldest.remove();
        }
        return id;
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
