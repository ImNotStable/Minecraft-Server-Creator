package io.github.imnotstable.msc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** A core in a temp workspace with a fake agent attached. */
final class CoreHarness implements AutoCloseable {
    final Core core;
    final FakeAgent agent = new FakeAgent();
    private AgentClient.Session session;

    CoreHarness(Path workspace, UnaryOperator<CoreConfig> configure) throws Exception {
        core = new Core(configure.apply(CoreConfig.forWorkspace(workspace)));
        core.hub().connectWait(Duration.ofMillis(300));
    }

    CoreHarness(Path workspace) throws Exception {
        this(workspace, c -> c);
    }

    CoreHarness withAgent() throws Exception {
        // The fake agent stands in for a running server, so let the hub wait for it to finish connecting.
        core.hub().agentExpected(() -> true);
        session = agent.connect(core.hub().address(), core.hub().token());
        core.hub().require();
        return this;
    }

    /** Call a tool with alternating name, value arguments. Values may be String, Number, Boolean or List of String. */
    ToolRegistry.Result call(String tool, Object... pairs) {
        return core.registry().call(tool, args(pairs));
    }

    /** Tool arguments from alternating name, value pairs, as {@link #call} takes them. */
    static JsonObject args(Object... pairs) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            String name = (String) pairs[i];
            if (v instanceof Number n) {
                args.addProperty(name, n);
            } else if (v instanceof Boolean b) {
                args.addProperty(name, b);
            } else if (v instanceof List<?> list) {
                JsonArray array = new JsonArray();
                list.forEach(item -> array.add(String.valueOf(item)));
                args.add(name, array);
            } else {
                args.addProperty(name, String.valueOf(v));
            }
        }
        return args;
    }

    /** Text of a call that must succeed. */
    String ok(String tool, Object... pairs) {
        ToolRegistry.Result r = call(tool, pairs);
        if (!r.ok()) {
            throw new AssertionError(tool + " failed: " + r.text());
        }
        return r.text();
    }

    /** Text of a call that must fail. */
    String error(String tool, Object... pairs) {
        ToolRegistry.Result r = call(tool, pairs);
        if (r.ok()) {
            throw new AssertionError(tool + " should have failed: " + r.text());
        }
        return r.text();
    }

    /**
     * Sets {@code server-port} in the dev server's server.properties to a port held closed, until the returned port is
     * closed. Without it player_test joins whatever answers on 127.0.0.1:25565, the port it falls back to when the file
     * names none, which on a developer's machine can be their own server.
     */
    static ClosedPort noGameServer(Path serverDir) throws IOException {
        ClosedPort port = ClosedPort.reserve();
        try {
            Files.createDirectories(serverDir);
            ServerPropertiesFile.set(serverDir.resolve(ServerPropertiesFile.NAME),
                    Map.of("server-port", Integer.toString(port.port())));
        } catch (IOException | RuntimeException e) {
            port.close();
            throw e;
        }
        return port;
    }

    @Override
    public void close() throws java.io.IOException {
        if (session != null) {
            session.connection().close();
        }
        core.close();
    }
}
