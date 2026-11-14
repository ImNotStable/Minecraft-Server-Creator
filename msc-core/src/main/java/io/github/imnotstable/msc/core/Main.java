package io.github.imnotstable.msc.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.imnotstable.msc.core.mcp.McpBridge;
import io.github.imnotstable.msc.core.server.EulaCommand;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point: an MCP server on stdin/stdout. Configuration comes from the environment (see {@link CoreConfig}).
 * {@code eula accept|status|revoke} instead runs the owner's one-time EULA command ({@link EulaCommand}).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (InvalidPathException e) {
            String why = unrepresentable(e, Charset.forName(System.getProperty("sun.jnu.encoding"), UTF_8));
            if (why == null) {
                throw e;
            }
            // One line the owner can act on, not a stack trace: Claude Code shows it as the server's error.
            System.err.println(why);
            System.exit(2);
        }
    }

    /**
     * The one line Main prints when a path it was given (MSC_WORKSPACE, for example) has characters the file name
     * encoding of this JVM ({@code sun.jnu.encoding}, from the locale: US-ASCII under {@code LANG=C}) cannot
     * represent, or null when {@code e} is another bad path. Only the locale changes that encoding: Java 25 ignores
     * {@code -Dsun.jnu.encoding}.
     */
    static String unrepresentable(InvalidPathException e, Charset fileNames) {
        if (fileNames.newEncoder().canEncode(e.getInput())) {
            return null;
        }
        return "MSC cannot use the path " + e.getInput() + ": this locale's file name encoding (" + fileNames.name()
                + ") cannot represent its characters. Start Claude Code under a UTF-8 locale, for example with "
                + "LANG=C.UTF-8 (or en_US.UTF-8) set.";
    }

    private static void run(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("eula")) {
            // The owner's own command, never an MCP tool: see EulaCommand and ADR 0020.
            Path installDir = installDir(Main.class.getProtectionDomain().getCodeSource());
            System.exit(EulaCommand.run(List.of(args).subList(1, args.length),
                    CoreConfig.fromEnvironment(System.getenv()).machineEula(), installDir, EulaCommand.console(),
                    Clock.systemUTC(), System.getProperty("user.name"), EulaCommand.hostName()));
        }
        if (args.length == 3 && args[0].equals("refresh-legacy-install")) {
            // Run by installMsc after each install: see LegacyInstall.
            // installMsc reads this as UTF-8, whatever the native encoding (#478).
            PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
            LegacyInstall.refresh(Path.of(args[1]), Path.of(args[2])).forEach(out::println);
            return;
        }
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
        CoreConfig config = CoreConfig.fromEnvironment(System.getenv());
        Path installDir = installDir(Main.class.getProtectionDomain().getCodeSource());
        if (installDir != null) {
            config = config.withInstallDir(installDir);
        }
        Core core = new Core(config);
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

    /**
     * The install folder of a distribution ({@code <dir>/lib/msc-core-*.jar}), or null when the core runs from
     * classes (tests, {@code gradle run}).
     */
    static Path installDir(CodeSource source) {
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            Path jar = Path.of(source.getLocation().toURI());
            Path lib = jar.getParent();
            if (!jar.getFileName().toString().endsWith(".jar") || lib == null || lib.getParent() == null
                    || !lib.getFileName().toString().equals("lib")) {
                return null;
            }
            return lib.getParent();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
