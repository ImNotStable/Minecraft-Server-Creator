package io.github.imnotstable.msc.core.fixture;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.FakeAgent;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
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
    private FakeServerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[12:00:00 INFO]: Starting minecraft server version 26.3");
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
        agent.onPack = p -> System.out.println("[12:00:05 INFO]: [fake] pack sent " + p.sha1());
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
                    long end = System.nanoTime() + Duration.ofMinutes(1).toNanos();
                    while (!Files.exists(Path.of("release.flag")) && System.nanoTime() < end) {
                        Thread.sleep(20);
                    }
                }
                break;
            }
            System.out.println("[12:00:05 INFO]: console: " + line);
            // "spam": ten more lines of 1,000 characters, more than a command's output holds (#611).
            if (line.equals("spam")) {
                for (int i = 0; i < 10; i++) {
                    System.out.println("[12:00:05 INFO]: spam " + i + " " + "s".repeat(1000));
                }
            }
            // Like vanilla's save-all; no-save.flag: a server that never confirms it.
            if (line.equals("save-all flush") && !Files.exists(Path.of("no-save.flag"))) {
                System.out.println("[12:00:05 INFO]: Saved the game");
            }
        }
        System.exit(0);
    }

    /** Connects like a real agent's CoreAddress does, or returns null when started without a core. */
    private static AgentClient.Session connect(AgentInfo info, OpRouter router) throws Exception {
        String address = System.getenv(Limits.ENV_CORE_ADDRESS);
        String token = System.getenv(Limits.ENV_TOKEN);
        if (address == null || token == null) {
            return null;
        }
        int colon = address.lastIndexOf(':');
        return AgentClient.connect(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)), token,
                info, router, new Connection.Listener() {
                }, Duration.ofSeconds(5));
    }
}
