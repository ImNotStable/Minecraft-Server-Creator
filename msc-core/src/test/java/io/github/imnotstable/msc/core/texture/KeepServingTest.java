package io.github.imnotstable.msc.core.texture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FailingPackHostMain;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.ServerManager;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The standalone pack host of a detached server ({@link ResourcePack#serve}): a start that is cut short never leaves a
 * process nobody can find, and one that does not come up leaves the build served in-process, with a one-line reason
 * (#744).
 */
class KeepServingTest {
    @TempDir
    Path dir;

    /** A built pack whose server is detached, so {@link ResourcePack#serve} hands it to the standalone host. */
    private ResourcePack builtPack() throws Exception {
        Path root = dir.resolve("pack");
        Files.createDirectories(root.resolve("assets/minecraft"));
        Files.writeString(root.resolve("assets/minecraft/a.txt"), "a");
        ResourcePack pack = new ResourcePack(root, dir.resolve(".msc/pack"), "127.0.0.1", 0, 46,
                () -> ServerManager.Lifetime.OUTLIVES);
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

    private ResourcePack slowHostPack() throws Exception {
        ResourcePack pack = builtPack();
        pack.hostCommand(List.of(java(), "-cp", slowHostClassPath(), PackHost.class.getName()));
        return pack;
    }

    private ProcessHandle hostOnceRecorded() throws Exception {
        Path pidFile = dir.resolve(".msc/pack-host.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(pidFile)) {
            assertThat(System.nanoTime()).as("pid file written").isLessThan(deadline);
            Thread.sleep(20);
        }
        return PidFile.peek(pidFile).orElseThrow();
    }

    private static int status(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try {
                return c.getResponseCode();
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    @Test
    void anInterruptWhileTheHostStartsLeavesItRecordedForCloseAndTheNextSession() throws Exception {
        ResourcePack pack = slowHostPack();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                pack.serve();
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
        });
        ProcessHandle host = hostOnceRecorded();
        Thread.sleep(200);
        call.interrupt();
        assertThat(thrown.get(30, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        assertThat(host.isAlive()).isTrue();
        // A detached server keeps it; the next session finds it by its pid file and stops it.
        pack.close(true);
        assertThat(host.isAlive()).isTrue();
        ResourcePack next = new ResourcePack(dir.resolve("pack"), dir.resolve(".msc/pack"), "127.0.0.1", 0, 46);
        assertThat(next.release().note()).contains("pid " + host.pid());
        host.onExit().get(30, TimeUnit.SECONDS);
        next.close();
    }

    @Test
    void closeWithoutADetachedServerStopsAHostCutShortWhileStarting() throws Exception {
        ResourcePack pack = slowHostPack();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                pack.serve();
            } catch (Exception expected) {
                // The interrupt below.
            }
        });
        ProcessHandle host = hostOnceRecorded();
        call.interrupt();
        call.join(30_000);
        pack.close(false);
        host.onExit().get(30, TimeUnit.SECONDS);
        assertThat(dir.resolve(".msc/pack-host.pid")).doesNotExist();
    }

    /**
     * #744: a host that does not come up is an answer, not a failure. The build is served in-process at the URL
     * returned, the reason is the exception's message (never a trace line), and the next serve tries the host again.
     */
    @Test
    void aHostThatDoesNotComeUpLeavesTheBuildServedInProcessAndSaysWhy() throws Exception {
        ResourcePack pack = builtPack();
        pack.hostCommand(List.of(java(), "-cp", System.getProperty("java.class.path"),
                FailingPackHostMain.class.getName()));
        ResourcePack.Serving failed = pack.serve();
        String url = failed.built().url();
        String port = url.substring(url.lastIndexOf(':') + 1, url.indexOf('/', url.lastIndexOf(':')));
        assertThat(failed.outlives()).isFalse();
        assertThat(failed.trouble()).isEqualTo("could not start a standalone pack host (port " + port
                + " is taken: Address already in use)");
        assertThat(status(url)).as("served by this session").isEqualTo(200);
        assertThat(dir.resolve(".msc/pack-host.pid")).doesNotExist();

        pack.hostCommand(List.of(java(), "-cp", System.getProperty("java.class.path"), PackHost.class.getName()));
        ResourcePack.Serving retried = pack.serve();
        assertThat(retried.trouble()).isNull();
        assertThat(retried.outlives()).isTrue();
        assertThat(retried.built().url()).isEqualTo(url);
        ProcessHandle host = PidFile.peek(dir.resolve(".msc/pack-host.pid")).orElseThrow();
        try {
            assertThat(status(url)).as("served by the standalone host").isEqualTo(200);
        } finally {
            pack.close(false);
            host.onExit().get(30, TimeUnit.SECONDS);
        }
    }

    /** #744: the reason is the line that says why, not the last line of a stack trace. */
    @Test
    void theReasonIsTheExceptionsMessageNeverATraceLine() {
        assertThat(ResourcePack.reason(List.of("Picked up JAVA_TOOL_OPTIONS: -Dx=y",
                "Exception in thread \"main\" java.net.BindException: port 32590 is "
                        + "taken: Address already in use", "\tat io.github.imnotstable.msc.core.tool.PortPatience.bind(x)",
                "Caused by: java.net.BindException: Address already in use", "\t... 2 more")))
                .isEqualTo("port 32590 is taken: Address already in use");
        assertThat(ResourcePack.reason(List.of("", "NOTE: Picked up JDK_JAVA_OPTIONS: -Dx=y",
                "port 1 is taken: Address already in use")))
                .isEqualTo("port 1 is taken: Address already in use");
        assertThat(ResourcePack.reason(List.of("Exception in thread \"main\" java.lang.OutOfMemoryError")))
                .isEqualTo("Exception in thread \"main\" java.lang.OutOfMemoryError");
        assertThat(ResourcePack.reason(List.of())).isEmpty();
    }
}
