package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * {@code /game} from the console runs on the console thread while the tick thread tracks who stands in a goal,
 * so {@link Game}'s goal set is used from several threads at once.
 */
class GameThreadsTest {
    @Test
    void theGoalSetKeepsEveryChangeMadeFromSeveralThreads() throws Exception {
        Set<UUID> inGoal = Game.newGoalSet();
        int threads = 4;
        int players = 20_000; // enough resizes that a plain HashSet loses entries on every run
        List<Callable<Integer>> work = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            work.add(() -> {
                int wrong = 0;
                List<UUID> mine = new ArrayList<>();
                for (int i = 0; i < players; i++) {
                    UUID id = UUID.randomUUID();
                    mine.add(id);
                    wrong += inGoal.add(id) ? 0 : 1; // steps into the goal: counted once
                    wrong += inGoal.add(id) ? 1 : 0; // still in it: not counted again
                }
                for (UUID id : mine) {
                    wrong += inGoal.contains(id) ? 0 : 1;
                    wrong += inGoal.remove(id) ? 0 : 1; // moved by /game or left the goal
                }
                return wrong;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int wrong = 0;
            for (Future<Integer> f : pool.invokeAll(work)) {
                wrong += f.get();
            }
            assertThat(wrong).as("lost or doubled goal entries").isZero();
            assertThat(inGoal).isEmpty();
        } finally {
            pool.shutdown();
        }
    }
}
