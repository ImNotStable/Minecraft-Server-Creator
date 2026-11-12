package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SlicedJobTest {
    @Test
    void jobsStopAtTheDeadlineAndResumeWhereTheyLeftOff() {
        MapWorld world = new MapWorld();
        Region region = Region.of("world", 0, 0, 0, 63, 0, 63);
        ReadJob<String> read = new ReadJob<>(world, region);

        int steps = 1;
        while (!read.step(0)) { // a deadline already passed: one slice per step
            steps++;
        }

        assertThat(steps).isEqualTo(5); // 4096 cells, 1024 per slice, then a call that only reports done
        assertThat(read.result().palette()).containsExactly(MapWorld.AIR);
    }

    @Test
    void editJobSkipsUnchangedBlocksAndRecordsWhatItReplaced() {
        MapWorld world = new MapWorld();
        world.set(0, 0, 0, "minecraft:dirt", false);
        UndoRecord.Builder<String> changes = new UndoRecord.Builder<>("world", 2);
        changes.add(0, 0, 0, "minecraft:stone");
        changes.add(1, 0, 0, MapWorld.AIR);

        UndoRecord<String> undo = new EditJob<>(world, changes.build().restore(), false).runNow();

        assertThat(undo.size()).isEqualTo(1);
        assertThat(undo.palette()).containsExactly("minecraft:dirt");
        assertThat(world.writes).isEqualTo(2); // the setup write and one real change
    }

    @Test
    void heightmapCoversEveryColumn() {
        MapWorld world = new MapWorld();
        world.set(2, 10, 1, "minecraft:stone", false);
        int[] heights = new HeightmapJob(world, 0, 0, 3, 2).runNow();
        assertThat(heights).containsExactly(-65, -65, -65, -65, -65, 10);
    }

    @Test
    void awaitPassesResultsAndRuntimeErrorsThroughAndTimesOut() {
        assertThat(MainThread.await(CompletableFuture.completedFuture("ok"), Duration.ofSeconds(1))).isEqualTo("ok");
        assertThatThrownBy(() -> MainThread.await(CompletableFuture.failedFuture(new IllegalArgumentException("bad")),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class).hasMessage("bad");
        assertThatThrownBy(() -> MainThread.await(new CompletableFuture<>(), Duration.ofMillis(10)))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TIMEOUT));
        assertThatThrownBy(() -> MainThread.await(CompletableFuture.failedFuture(new Exception("checked")),
                Duration.ofSeconds(1))).isInstanceOfSatisfying(OpException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.INTERNAL));
    }
}
