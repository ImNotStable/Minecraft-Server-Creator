package io.github.imnotstable.msc.core;

import io.github.imnotstable.msc.core.mcp.McpBridge;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

/** Entry point: an MCP server on stdin/stdout. Configuration comes from the environment (see {@link CoreConfig}). */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // stdout carries MCP frames only; anything a library prints must go to stderr.
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        CountDownLatch done = new CountDownLatch(1);
        InputStream in = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                int b = System.in.read();
                if (b < 0) {
                    done.countDown();
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws java.io.IOException {
                int n = System.in.read(buf, off, len);
                if (n < 0) {
                    done.countDown();
                }
                return n;
            }
        };
        Core core = new Core(CoreConfig.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                core.close();
            } catch (Exception ignored) {
                // Exiting anyway.
            }
        }, "msc-core-shutdown"));
        var server = McpBridge.start(core.registry(), in, protocolOut);
        // The client closing stdin is how an MCP stdio session ends.
        done.await();
        server.closeGracefully();
        System.exit(0);
    }
}
