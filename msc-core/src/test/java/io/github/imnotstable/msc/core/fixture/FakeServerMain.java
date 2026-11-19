package io.github.imnotstable.msc.core.fixture;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.FakeAgent;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A stand-in for a Paper server process: logs like one, connects an agent from the environment the
 * core gave it, and stops on "stop". Marker files in its working directory script its behaviour.
 */
public final class FakeServerMain {
    /** The server port it holds while it runs ({@code bind-port.txt}). */
    private static final java.net.ServerSocket[] BOUND = new java.net.ServerSocket[1];

    private FakeServerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[12:00:00 INFO]: Starting minecraft server version 26.3");
        // bind-port.txt: the server's port, bound as Paper binds it, for a port the core does not know (#755).
        if (Files.exists(Path.of("bind-port.txt"))) {
            int port = Integer.parseInt(Files.readString(Path.of("bind-port.txt")).strip());
            System.out.println("[12:00:01 INFO]: Starting Minecraft server on 127.0.0.1:" + port);
            try {
                BOUND[0] = new java.net.ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress());
            } catch (java.net.BindException e) {
                System.out.println("[12:00:01 WARN]: **** FAILED TO BIND TO PORT!");
                System.out.println("[12:00:01 WARN]: The exception was: java.net.BindException: Address already in use");
                System.out.println("[12:00:01 WARN]: Perhaps a server is already running on that port?");
                System.out.flush();
                System.exit(1);
            }
        }
        if (Files.exists(Path.of("crash.flag"))) {
            System.out.println("[12:00:01 ERROR]: Failed to start the minecraft server");
            System.exit(1);
        }
        // long-lines.flag: ten clean lines of 2,000 characters, more than a default cap holds (#611).
        // long-exit.flag: the same, then the server exits before it is ready.
        boolean longExit = Files.exists(Path.of("long-exit.flag"));
        if (longExit || Files.exists(Path.of("long-lines.flag"))) {
            for (int i = 0; i < 10; i++) {
                System.out.println("[12:00:01 INFO]: step " + i + " " + "s".repeat(2000));
            }
            if (longExit) {
                System.out.flush();
                System.exit(0);
            }
        }
        if (Files.exists(Path.of("plugins", "demo.jar"))) {
            System.out.println("[12:00:01 INFO]: [Demo] Enabling Demo v" + Files.readString(Path.of("plugins", "demo.jar")).trim());
        }
        // many-errors.flag: a noisy start, 300 errors with traces, far more than any cap (#473).
        if (Files.exists(Path.of("many-errors.flag"))) {
            for (int i = 0; i < 300; i++) {
                System.out.println("[12:00:01 ERROR]: Could not load plugin part" + i + ": something broke number " + i
                        + " in a long-ish message text\njava.lang.IllegalStateException: failure " + i
                        + "\n\tat com.example.demo.Part" + i + ".onEnable(Part" + i + ".java:" + i + ") ~[demo.jar:?]");
            }
        }
        if (Files.exists(Path.of("error.flag"))) {
            System.out.println("[12:00:01 ERROR]: Error occurred while enabling Demo v1.0 (Is it up to date?)");
            System.out.println("java.lang.IllegalStateException: arena missing");
            System.out.println("\tat com.example.demo.Demo.onEnable(Demo.java:17) ~[demo.jar:?]");
            System.out.println("\tat org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:288) ~[paper-api.jar:?]");
        }
        // done-crash.flag: ready, then dead in its first tick before the agent connects (#337).
        if (Files.exists(Path.of("done-crash.flag"))) {
            System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
            System.out.println("[12:00:03 ERROR]: Encountered an unexpected exception");
            System.out.println("java.lang.IllegalStateException: tick failed");
            System.out.println("\tat com.example.demo.Demo.tick(Demo.java:30) ~[demo.jar:?]");
            System.out.flush();
            System.exit(1);
        }
        // late-agent.flag: the agent connects only after "Done", as Minestom's built-in one can.
        boolean late = Files.exists(Path.of("late-agent.flag"));
        if (late) {
            System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
            Thread.sleep(800);
        }
        FakeAgent agent = new FakeAgent();
        // Each pack sent, as a line a test counts (#667).
        // And its URL in fake-pack-sent.txt, read after the core that sent it is gone (#753): what joining players get.
        agent.onPack = p -> {
            System.out.println("[12:00:05 INFO]: [fake] pack sent " + p.sha1());
            try {
                Files.writeString(Path.of("fake-pack-sent.txt"), ProcessHandle.current().pid() + " " + p.url() + "\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        OpRouter router = agent.router();
        // no-agent.flag: the agent never connects, as when it failed to load.
        AgentClient.Session session = Files.exists(Path.of("no-agent.flag")) ? null
                : connect(new AgentInfo("paper", "fake", "26.3", router.operations()), router);
        if (session != null) {
            session.connection().sendEvent(Events.SERVER_READY, new JsonObject());
        }
        if (!late) {
            System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("stop")) {
                System.out.println("[12:00:09 INFO]: Stopping server");
                // linger-stop.flag: the agent leaves without a word, then the process lingers until release.flag
                // exists (at most a minute), as a real server saving its worlds does (#703).
                if (session != null && Files.exists(Path.of("linger-stop.flag"))) {
                    session.connection().close();
                    System.out.println("[12:00:09 INFO]: agent gone, lingering");
                    linger();
                } else if (session != null && Files.exists(Path.of("close-console-stop.flag"))) {
                    // As the Minestom template does (#716): its agent says so and leaves, its console closes stdin.
                    session.connection().sendEvent(Events.SERVER_STOPPING, new JsonObject());
                    session.connection().close();
                    in.close();
                    System.out.println("[12:00:09 INFO]: console closed, lingering");
                    linger();
                } else if (session != null && Files.exists(Path.of("hold-stop.flag"))) {
                    // The agent stays connected while the server saves its worlds (#723).
                    System.out.println("[12:00:09 INFO]: agent stays, lingering");
                    linger();
                } else if (session != null && Files.exists(Path.of("announce-stop.flag"))) {
                    // The agent says so and leaves; the console stays open but nothing reads it any more (#723).
                    session.connection().sendEvent(Events.SERVER_STOPPING, new JsonObject());
                    session.connection().close();
                    System.out.println("[12:00:09 INFO]: announced, lingering");
                    linger();
                } else if (session != null) {
                    // As a real agent does when its server begins to stop (#711).
                    session.connection().sendEvent(Events.SERVER_STOPPING, new JsonObject());
                }
                break;
            }
            System.out.println("[12:00:05 INFO]: console: " + line);
            // The agent says the server is stopping, and the server runs on (#729): "announce" keeps the agent
            // connected, "unload" closes it (a plugin manager, Minestom's MscAgent.stop()), "load" connects a new
            // one, as the second half of Paper's /reload does.
            if (session != null && (line.equals("announce") || line.equals("unload"))) {
                session.connection().sendEvent(Events.SERVER_STOPPING, new JsonObject());
                if (line.equals("unload")) {
                    session.connection().close();
                    session = null;
                }
            } else if (session == null && line.equals("load")) {
                session = connect(new AgentInfo("paper", "fake", "26.3", router.operations()), router);
            }
            // "spam": ten more lines of 1,000 characters, more than a command's output holds (#611).
            if (line.equals("spam")) {
                for (int i = 0; i < 10; i++) {
                    System.out.println("[12:00:05 INFO]: spam " + i + " " + "s".repeat(1000));
                }
            }
            // Like vanilla's save-off with autosave already off (autosave-off.flag).
            if (line.equals("save-off") && Files.exists(Path.of("autosave-off.flag"))) {
                System.out.println("[12:00:05 INFO]: Saving is already turned off");
            }
            // Like vanilla's save-all; no-save.flag: a server that never confirms it.
            if (line.equals("save-all flush") && !Files.exists(Path.of("no-save.flag"))) {
                System.out.println("[12:00:05 INFO]: Saved the game");
            }
        }
        System.exit(0);
    }

    /** Until release.flag exists, at most a minute. */
    private static void linger() throws InterruptedException {
        long end = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (!Files.exists(Path.of("release.flag")) && System.nanoTime() < end) {
            Thread.sleep(20);
        }
    }

    /**
     * Connects like a real agent's CoreAddress does, or returns null when started without a core. reconnect.flag: when
     * the connection closes, it connects again to the same address with the same token, as AgentLink does, until a core
     * takes it or refuses it (ADR 0050).
     */
    private static AgentClient.Session connect(AgentInfo info, OpRouter router) throws Exception {
        String address = System.getenv(Limits.ENV_CORE_ADDRESS);
        String token = System.getenv(Limits.ENV_TOKEN);
        if (address == null || token == null) {
            return null;
        }
        int colon = address.lastIndexOf(':');
        String host = address.substring(0, colon);
        int port = Integer.parseInt(address.substring(colon + 1));
        boolean reconnect = Files.exists(Path.of("reconnect.flag"));
        Connection.Listener listener = new Connection.Listener() {
            @Override
            public void onClose(Throwable cause) {
                if (reconnect) {
                    Thread.ofVirtual().start(() -> reconnect(host, port, token, info, router, this));
                }
            }
        };
        return AgentClient.connect(host, port, token, info, router, listener, Duration.ofSeconds(5));
    }

    private static void reconnect(String host, int port, String token, AgentInfo info, OpRouter router,
                                  Connection.Listener listener) {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            try {
                AgentClient.connect(host, port, token, info, router, listener, Duration.ofSeconds(5));
                System.out.println("[12:00:10 INFO]: agent reconnected");
                return;
            } catch (OpException e) {
                System.out.println("[12:00:10 INFO]: msc-core refused the agent: " + e.getMessage());
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }
}
