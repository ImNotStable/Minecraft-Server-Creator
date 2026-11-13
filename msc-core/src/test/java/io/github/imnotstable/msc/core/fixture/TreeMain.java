package io.github.imnotstable.msc.core.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A build with a child process, like Gradle and its workers: starts {@link SleepMain}, writes {@code tree-pids} (the
 * child's pid, then its own) and sleeps.
 */
public final class TreeMain {
    private TreeMain() {
    }

    public static void main(String[] args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                SleepMain.class.getName())).start();
        Files.move(Files.writeString(Path.of("tree-pids.tmp"), child.pid() + " " + ProcessHandle.current().pid()),
                Path.of("tree-pids"));
        Thread.sleep(120_000);
    }
}
