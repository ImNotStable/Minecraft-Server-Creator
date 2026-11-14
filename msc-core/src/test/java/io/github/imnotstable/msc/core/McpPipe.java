package io.github.imnotstable.msc.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.mcp.McpBridge;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * An MCP client talking raw JSON-RPC lines to {@link McpBridge} over in-process pipes, the way a client does over
 * stdio, so a test sees exactly what the SDK lets through and answers (#510). Arguments are JSON text, so a test can
 * send what Gson would not write, such as {@code 1e400}.
 */
final class McpPipe implements AutoCloseable {
    /** A tools/call answer: the text content, isError, or the JSON-RPC error when the SDK refused the request. */
    record Answer(String text, boolean error, JsonObject rpcError) {
    }

    private final PipedOutputStream toServer = new PipedOutputStream();
    private final PipedInputStream fromServer = new PipedInputStream(1 << 20);
    private final McpSyncServer server;
    private final BufferedReader reader;
    private int id;

    McpPipe(ToolRegistry registry) throws Exception {
        PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream(fromServer);
        server = McpBridge.start(registry, serverIn, serverOut);
        reader = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8));
        request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    /** Calls {@code tool} with {@code arguments}, the JSON text of the arguments value. */
    Answer call(String tool, String arguments) throws Exception {
        JsonObject reply = request("tools/call", "{\"name\":" + JsonParser.parseString("\"" + tool + "\"")
                + ",\"arguments\":" + arguments + "}");
        if (reply.has("error")) {
            return new Answer(null, true, reply.getAsJsonObject("error"));
        }
        JsonObject result = reply.getAsJsonObject("result");
        StringBuilder text = new StringBuilder();
        result.getAsJsonArray("content").forEach(c -> {
            JsonObject o = c.getAsJsonObject();
            if (o.get("type").getAsString().equals("text")) {
                text.append(o.get("text").getAsString());
            }
        });
        return new Answer(text.toString(), result.has("isError") && result.get("isError").getAsBoolean(), null);
    }

    /** Sends a request and returns the response with its id; notifications in between are skipped. */
    JsonObject request(String method, String params) throws Exception {
        int mine = ++id;
        send("{\"jsonrpc\":\"2.0\",\"id\":" + mine + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        return CompletableFuture.supplyAsync(() -> {
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new IllegalStateException("server closed the stream");
                    }
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    if (message.has("id") && message.get("id").getAsInt() == mine) {
                        return message;
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).get(60, TimeUnit.SECONDS);
    }

    private void send(String json) throws IOException {
        toServer.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
        toServer.close();
    }
}
