package io.github.imnotstable.msc.core.texture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.PidFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** server_detach's standalone pack host: a start that is cut short never leaves a process nobody can find. */
class KeepServingTest {
    @TempDir
    Path dir;

    private ResourcePack builtPack() throws Exception {
        Path root = dir.resolve("pack");
        Files.createDirectories(root.resolve("assets/minecraft"));
        Files.writeString(root.resolve("assets/minecraft/a.txt"), "a");
        ResourcePack pack = new ResourcePack(root, dir.resolve(".msc/pack"), "127.0.0.1", 0, 46);
        pack.build("test");
        return pack;
    }

    /** A "java" that starts and never listens, like a host JVM slow to come up. */
    private Path slowJava() throws Exception {
        Path script = dir.resolve("slow-java.sh");
        Files.writeString(script, "#!/bin/sh\nexec sleep 60\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    @Test
    void anInterruptWhileTheHostStartsLeavesItRecordedForCloseAndTheNextSession() throws Exception {
        ResourcePack pack = builtPack();
        Path java = slowJava();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                pack.keepServing(java.toString(), "unused");
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
        });
        Path pidFile = dir.resolve(".msc/pack-host.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(pidFile)) {
            assertThat(System.nanoTime()).as("pid file written").isLessThan(deadline);
            Thread.sleep(20);
        }
        Thread.sleep(200);
        call.interrupt();
        assertThat(thrown.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        ProcessHandle host = PidFile.peek(pidFile).orElseThrow();
        assertThat(host.isAlive()).isTrue();
        // A detached server keeps it; the next session finds it by its pid file and stops it.
        pack.close(true);
        assertThat(host.isAlive()).isTrue();
        ResourcePack next = new ResourcePack(dir.resolve("pack"), dir.resolve(".msc/pack"), "127.0.0.1", 0, 46);
        assertThat(next.stopLeftoverHost()).contains("pid " + host.pid());
        host.onExit().get(5, TimeUnit.SECONDS);
        next.close();
    }

    @Test
    void closeWithoutADetachedServerStopsAHostCutShortWhileStarting() throws Exception {
        ResourcePack pack = builtPack();
        Path java = slowJava();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                pack.keepServing(java.toString(), "unused");
            } catch (Exception expected) {
                // The interrupt below.
            }
        });
        Path pidFile = dir.resolve(".msc/pack-host.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(pidFile)) {
            assertThat(System.nanoTime()).as("pid file written").isLessThan(deadline);
            Thread.sleep(20);
        }
        ProcessHandle host = PidFile.peek(pidFile).orElseThrow();
        call.interrupt();
        call.join(5000);
        pack.close(false);
        host.onExit().get(5, TimeUnit.SECONDS);
        assertThat(pidFile).doesNotExist();
    }
}
