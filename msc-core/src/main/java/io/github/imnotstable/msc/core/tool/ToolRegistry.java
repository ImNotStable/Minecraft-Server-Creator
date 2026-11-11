package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** All tools by name. Every call goes through {@link #call}, which turns failures into text and logs the call. */
public final class ToolRegistry {
    /** What the model sees. {@code ok} is false when the text is an error; {@code png} is null without an image. */
    public record Result(String text, boolean ok, byte[] png) {
        public Result(String text, boolean ok) {
            this(text, ok, null);
        }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ToolLog log;

    public ToolRegistry(ToolLog log) {
        this.log = log;
    }

    public ToolRegistry add(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalStateException("duplicate tool " + tool.name());
        }
        return this;
    }

    public ToolRegistry add(String name, String description, Schema schema, Tool.Handler handler) {
        return add(new Tool(name, description, schema.build(), handler));
    }

    public ToolRegistry addImage(String name, String description, Schema schema, Tool.ImageHandler handler) {
        return add(new Tool(name, description, schema.build(), handler));
    }

    public Collection<Tool> tools() {
        return tools.values();
    }

    public Result call(String name, JsonObject arguments) {
        long start = System.nanoTime();
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        String text;
        boolean ok = false;
        Tool.Output output = null;
        Tool tool = tools.get(name);
        if (tool == null) {
            text = "error: unknown tool " + name;
        } else {
            try {
                output = tool.handler().output(new Args(args));
                text = output.text();
                ok = true;
            } catch (ToolException e) {
                text = "error: " + e.getMessage();
            } catch (OpException e) {
                text = "error: agent answered " + e.code() + ": " + e.detail();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "error: interrupted";
            } catch (Exception e) {
                text = "error: internal: " + e;
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.record(name, ok, ms, args.toString().length(), text.length(), ok ? null : text,
                output == null ? 0 : output.imageTokens());
        return new Result(text, ok, output == null ? null : output.png());
    }
}
