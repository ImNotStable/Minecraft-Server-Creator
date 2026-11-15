package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #621: every run gets a new folder of its own, also when runs start in the same second. */
class RunFolderTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 15, 8, 12, 30);

    @TempDir
    Path dir;

    @Test
    void aFolderIsNamedByItsStartSecondAndCreatedWithItsParents() throws Exception {
        Path runs = dir.resolve("a/runs");

        assertThat(RunFolder.claim(runs, START)).isEqualTo(runs.resolve("20261115-081230")).isEmptyDirectory();
    }

    @Test
    void aTakenNameIsNeverReusedButTheNextFreeOneTaken() throws Exception {
        Files.createDirectories(dir.resolve("20261115-081230"));
        Files.writeString(dir.resolve("20261115-081230/results.jsonl"), "another run's line\n");
        Files.writeString(dir.resolve("20261115-081230-2"), "a file, not a folder");

        Path claimed = RunFolder.claim(dir, START);

        assertThat(claimed).isEqualTo(dir.resolve("20261115-081230-3")).isEmptyDirectory();
        assertThat(dir.resolve("20261115-081230/results.jsonl")).hasContent("another run's line");
        assertThat(dir.resolve("20261115-081230-2")).isRegularFile();
    }

    @Test
    void runsStartingInTheSameSecondEachGetTheirOwnFolder() throws Exception {
        int runs = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Path>> claims = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(runs)) {
            Callable<Path> claim = () -> {
                go.await();
                return RunFolder.claim(dir, START);
            };
            for (int i = 0; i < runs; i++) {
                claims.add(pool.submit(claim));
            }
            go.countDown();
            List<Path> folders = new ArrayList<>();
            for (Future<Path> f : claims) {
                folders.add(f.get());
            }

            assertThat(folders).doesNotHaveDuplicates().allSatisfy(f -> assertThat(f).isEmptyDirectory());
            assertThat(folders.stream().map(f -> f.getFileName().toString()))
                    .containsExactlyInAnyOrder("20261115-081230", "20261115-081230-2", "20261115-081230-3",
                            "20261115-081230-4", "20261115-081230-5", "20261115-081230-6", "20261115-081230-7",
                            "20261115-081230-8");
        }
    }
}
