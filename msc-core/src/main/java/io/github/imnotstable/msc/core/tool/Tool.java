package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonObject;

/**
 * One MCP tool. Results are plain text shaped for a model: short, no decoration, sized by a budget.
 *
 * @param inputSchema JSON Schema of the arguments (see {@link Schema})
 */
public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {
    /** Runs the tool. Throw {@link ToolException} for anything the caller can fix. */
    @FunctionalInterface
    public interface Handler {
        String call(Args args) throws Exception;
    }
}
