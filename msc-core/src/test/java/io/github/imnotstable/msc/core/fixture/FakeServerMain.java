package io.github.imnotstable.msc.core.fixture;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.FakeAgent;
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
        if (Files.exists(Path.of("plugins", "demo.jar"))) {
            System.out.println("[12:00:01 INFO]: [Demo] Enabling Demo v" + Files.readString(Path.of("plugins", "demo.jar")).trim());
        }
        if (Files.exists(Path.of("error.flag"))) {
            System.out.println("[12:00:01 ERROR]: Error occurred while enabling Demo v1.0 (Is it up to date?)");
            System.out.println("java.lang.IllegalStateException: arena missing");
            System.out.println("\tat com.example.demo.Demo.onEnable(Demo.java:17) ~[demo.jar:?]");
            System.out.println("\tat org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:288) ~[paper-api.jar:?]");
        }
        OpRouter router = new FakeAgent().router();
        AgentClient.Session session = AgentClient.connectFromEnvironment(
                new AgentInfo("paper", "fake", "26.3", router.operations()), router, new Connection.Listener() {
                }, Duration.ofSeconds(5));
        if (session != null) {
            session.connection().sendEvent(Events.SERVER_READY, new JsonObject());
        }
        System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("stop")) {
                System.out.println("[12:00:09 INFO]: Stopping server");
                break;
            }
            System.out.println("[12:00:05 INFO]: console: " + line);
        }
        System.exit(0);
    }
}
