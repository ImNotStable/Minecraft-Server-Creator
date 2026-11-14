package io.github.imnotstable.msc.core.texture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.PidFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
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

    /**
     * A class path whose {@link PackHost} starts and never listens, like a host JVM slow to come up. It is the test
     * JVM's own java running a generated stand-in class, so the test works on Windows too (#338).
     */
    private String slowHostClassPath() throws Exception {
        ClassDesc host = ClassDesc.of(PackHost.class.getName());
        byte[] bytes = ClassFile.of().build(host, c -> c
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                .withMethodBody("main", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                                .ldc(120_000L)
                                .invokestatic(ClassDesc.of("java.lang.Thread"), "sleep",
                                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long))
                                .return_()));
        Path classes = dir.resolve("slow-host");
        Path file = classes.resolve(PackHost.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return classes.toString();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    void anInterruptWhileTheHostStartsLeavesItRecordedForCloseAndTheNextSession() throws Exception {
        ResourcePack pack = builtPack();
        String classPath = slowHostClassPath();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                pack.keepServing(java(), classPath);
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
        });
        Path pidFile = dir.resolve(".msc/pack-host.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(pidFile)) {
            assertThat(System.nanoTime()).as("pid file written").isLessThan(deadline);
            Thread.sleep(20);
        }
        Thread.sleep(200);
        call.interrupt();
        assertThat(thrown.get(30, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        ProcessHandle host = PidFile.peek(pidFile).orElseThrow();
        assertThat(host.isAlive()).isTrue();
        // A detached server keeps it; the next session finds it by its pid file and stops it.
        pack.close(true);
        assertThat(host.isAlive()).isTrue();
        ResourcePack next = new ResourcePack(dir.resolve("pack"), dir.resolve(".msc/pack"), "127.0.0.1", 0, 46);
        assertThat(next.stopLeftoverHost()).contains("pid " + host.pid());
        host.onExit().get(30, TimeUnit.SECONDS);
        next.close();
    }

    @Test
    void closeWithoutADetachedServerStopsAHostCutShortWhileStarting() throws Exception {
        ResourcePack pack = builtPack();
        String classPath = slowHostClassPath();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                pack.keepServing(java(), classPath);
            } catch (Exception expected) {
                // The interrupt below.
            }
        });
        Path pidFile = dir.resolve(".msc/pack-host.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(pidFile)) {
            assertThat(System.nanoTime()).as("pid file written").isLessThan(deadline);
            Thread.sleep(20);
        }
        ProcessHandle host = PidFile.peek(pidFile).orElseThrow();
        call.interrupt();
        call.join(30_000);
        pack.close(false);
        host.onExit().get(30, TimeUnit.SECONDS);
        assertThat(pidFile).doesNotExist();
    }
}
