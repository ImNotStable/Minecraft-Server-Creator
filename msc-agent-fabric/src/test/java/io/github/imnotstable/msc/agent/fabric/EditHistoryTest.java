package io.github.imnotstable.msc.agent.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditHistoryTest {
    private static UndoRecord record(int blocks) {
        return new UndoRecord("world", new long[blocks], new int[blocks], List.of());
    }

    @Test
    void dropsOldestEditsOverTheBlockBudget() {
        EditHistory history = new EditHistory(10);
        String first = history.add(record(6));
        String second = history.add(record(4));
        String third = history.add(record(3));

        assertThat(history.take(first)).isEmpty();
        assertThat(history.take(second)).isPresent();
        assertThat(history.take(third)).isPresent();
        assertThat(history.rememberedBlocks()).isZero();
    }

    @Test
    void anEditCanBeTakenOnlyOnce() {
        EditHistory history = new EditHistory(10);
        String id = history.add(record(2));
        assertThat(history.take(id)).isPresent();
        assertThat(history.take(id)).isEmpty();
    }

    @Test
    void idsAreUniqueAndOversizedEditsAreNotKept() {
        EditHistory history = new EditHistory(5);
        String big = history.add(record(6));
        String small = history.add(record(1));
        assertThat(big).isNotEqualTo(small);
        assertThat(history.take(big)).isEmpty();
        assertThat(history.rememberedBlocks()).isEqualTo(1);
    }
}
