package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonObject;

/**
 * One MCP tool. Results are plain text shaped for a model: short, no decoration, sized by a budget.
 *
 * @param inputSchema JSON Schema of the arguments (see {@link Schema})
 */
public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {
    /**
     * What a call returns: text, and for image tools one PNG.
     *
     * @param png         PNG bytes, or null for a text-only result
     * @param imageTokens estimated model tokens the image costs, logged for msc-bench
     */
    public record Output(String text, byte[] png, int imageTokens) {
    }

    /** Runs the tool. Throw {@link ToolException} for anything the caller can fix. */
    @FunctionalInterface
    public interface Handler {
        String call(Args args) throws Exception;

        default Output output(Args args) throws Exception {
            return new Output(call(args), null, 0);
        }
    }

    /** A handler whose result carries one image next to its text. */
    @FunctionalInterface
    public interface ImageHandler extends Handler {
        Output render(Args args) throws Exception;

        @Override
        default String call(Args args) throws Exception {
            return render(args).text();
        }

        @Override
        default Output output(Args args) throws Exception {
            return render(args);
        }
    }
}
