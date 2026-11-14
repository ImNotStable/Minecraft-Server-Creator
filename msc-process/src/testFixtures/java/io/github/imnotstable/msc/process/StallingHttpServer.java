package io.github.imnotstable.msc.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An HTTP server for one kind of answer per instance: status 200 with a {@code Content-Length} of {@code length},
 * then {@code sent} bytes of the body, one every {@code pause}, and then nothing more until the client hangs up (a
 * stall) unless all {@code length} bytes were sent. The download tests of msc-process, msc-core and msc-bench share it.
 */
public final class StallingHttpServer implements AutoCloseable {
    private final ServerSocket socket;
    private final int length;
    private final int sent;
    private final Duration pause;
    private final CountDownLatch answered = new CountDownLatch(1);
    private final CountDownLatch hungUp = new CountDownLatch(1);
    private final Thread thread;

    public StallingHttpServer(int length, int sent, Duration pause) throws IOException {
        this.socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        this.length = length;
        this.sent = sent;
        this.pause = pause;
        this.thread = Thread.ofVirtual().start(this::serve);
    }

    /** A URL on this server; every path gets the same answer. */
    public String url(String path) {
        return "http://127.0.0.1:" + socket.getLocalPort() + path;
    }

    /** True once the server has sent its response headers, within {@code wait}. */
    public boolean answered(Duration wait) throws InterruptedException {
        return answered.await(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** True once the client closed the connection, within {@code wait}. */
    public boolean hungUp(Duration wait) throws InterruptedException {
        return hungUp.await(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void serve() {
        try (Socket client = socket.accept()) {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            readHeaders(in);
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + length + "\r\nContent-Type: application/octet-stream"
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            answered.countDown();
            for (int i = 0; i < sent; i++) {
                out.write('x');
                out.flush();
                if (pause.isPositive()) {
                    Thread.sleep(pause);
                }
            }
            while (in.read() >= 0) {
                // stalled: wait for the client to hang up
            }
            hungUp.countDown();
        } catch (IOException e) {
            hungUp.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readHeaders(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("client hung up before its request was complete");
            }
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        thread.interrupt();
    }
}
