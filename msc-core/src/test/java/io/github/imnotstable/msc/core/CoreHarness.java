package io.github.imnotstable.msc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        session = agent.connect(core.hub().address(), core.hub().token());
        core.hub().require();
        return this;
    }

    /** Call a tool with alternating name, value arguments. Values may be String, Number, Boolean or List of String. */
    ToolRegistry.Result call(String tool, Object... pairs) {
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
        return core.registry().call(tool, args);
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

    @Override
    public void close() throws java.io.IOException {
        if (session != null) {
            session.connection().close();
        }
        core.close();
    }
}
