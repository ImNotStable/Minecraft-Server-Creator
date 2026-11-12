package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.mcp.McpBridge;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Speaks raw MCP JSON-RPC to the server over pipes, the way a client does over stdio. */
class McpBridgeTest {
    @Test
    void listsAndCallsToolsOverStdio(@TempDir Path workspace) throws Exception {
        try (CoreHarness h = new CoreHarness(workspace).withAgent();
             PipedOutputStream toServer = new PipedOutputStream();
             PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 16);
             PipedOutputStream serverOut = new PipedOutputStream();
             PipedInputStream fromServer = new PipedInputStream(serverOut, 1 << 20)) {
            McpSyncServer server = McpBridge.start(h.core.registry(), serverIn, serverOut);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8));
            try {
                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                JsonObject init = read(reader, 1).getAsJsonObject("result");
                assertThat(init.getAsJsonObject("serverInfo").get("name").getAsString()).isEqualTo("msc-core");
                assertThat(init.get("instructions").getAsString()).contains("dev_cycle").contains("acceptEula=true")
                        .contains("never accept it for them");
                send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
                JsonObject listed = read(reader, 2);
                JsonArray tools = listed.getAsJsonObject("result").getAsJsonArray("tools");
                List<String> names = new ArrayList<>();
                tools.forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
                assertThat(names).containsExactly("msc_status", "msc_stats", "project_scaffold", "server_setup",
                        "server_start", "server_stop", "dev_cycle", "server_logs", "server_command", "api_search",
                        "api_class", "world_slice", "world_heightmap", "world_render", "world_summary", "build_fill", "build_set",
                        "build_structure", "build_copy", "build_undo", "structure_save", "structure_load",
                        "texture_create", "texture_generate", "texture_recolor", "texture_animate", "model_create", "texture_view",
                        "pack_check", "pack_build");
                // The tool list is paid for in every conversation: keep it small.
                assertThat(listed.toString().length()).isLessThan(14200);
                JsonObject fill = tools.get(names.indexOf("build_fill")).getAsJsonObject().getAsJsonObject("inputSchema");
                assertThat(fill.getAsJsonArray("required").toString()).isEqualTo("[\"region\",\"block\"]");

                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"msc_status\",\"arguments\":{}}}");
                JsonObject status = read(reader, 3).getAsJsonObject("result");
                assertThat(status.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                        .startsWith("msc-core ").contains("agent: paper fake");
                assertThat(status.get("isError").getAsBoolean()).isFalse();

                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"build_undo\",\"arguments\":{}}}");
                JsonObject failed = read(reader, 4).getAsJsonObject("result");
                assertThat(failed.get("isError").getAsBoolean()).isTrue();
                assertThat(failed.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                        .isEqualTo("error: nothing to undo");

                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"world_render\","
                        + "\"arguments\":{\"region\":\"0,60,0 3,63,3\",\"view\":\"top\",\"size\":64}}}");
                JsonArray rendered = read(reader, 5).getAsJsonObject("result").getAsJsonArray("content");
                JsonObject image = rendered.get(0).getAsJsonObject();
                assertThat(image.get("type").getAsString()).isEqualTo("image");
                assertThat(image.get("mimeType").getAsString()).isEqualTo("image/png");
                byte[] png = java.util.Base64.getDecoder().decode(image.get("data").getAsString());
                assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png))).isNotNull();
                assertThat(rendered.get(1).getAsJsonObject().get("text").getAsString()).startsWith("Top view");
            } finally {
                server.close();
            }
        }
    }

    private static void send(PipedOutputStream out, String json) throws Exception {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Next response with the given id; notifications in between are skipped. */
    private static JsonObject read(BufferedReader reader, int id) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new IllegalStateException("server closed the stream");
                    }
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    if (message.has("id") && message.get("id").getAsInt() == id) {
                        return message;
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }).get(15, TimeUnit.SECONDS);
    }
}
