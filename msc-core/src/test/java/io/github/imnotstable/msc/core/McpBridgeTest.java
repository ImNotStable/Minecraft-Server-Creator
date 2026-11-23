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
                        .contains("never accept it for them")
                        .contains("(Paper, Purpur, Folia, Fabric, Quilt, NeoForge, Forge, Minestom or Velocity)")
                        .contains("Paper, Purpur, Folia, Fabric, Quilt, NeoForge and Forge need the user's agreement");
                send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
                JsonObject listed = read(reader, 2);
                JsonArray tools = listed.getAsJsonObject("result").getAsJsonArray("tools");
                List<String> names = new ArrayList<>();
                tools.forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
                assertThat(names).containsExactly("msc_status", "project_scaffold", "server_setup", "world_create",
                        "plugins", "server_start", "server_stop", "server_detach", "dev_cycle", "server_logs", "server_command", "server_wait", "api_search",
                        "api_class", "world_slice", "world_heightmap", "world_render", "world_summary", "world_entities", "build_undo",
                        "structure_save", "build_batch",
                        "texture", "texture_view", "model_create", "pack_asset", "pack", "player_test", "msc_workspace");
                // The tool list is paid for in every conversation: keep it small. ADR 0019 folded five build tools
                // into build_batch and msc_stats into msc_status (18.5k chars to 15.5k). ADR 0057 raised it by 400
                // for the plugins tool, a capability no existing tool could carry; ADR 0068 by 200 for guide= and
                // review=, which cost nothing until called and replace re-reading builds by eye; ADR 0075 by 1200
                // for world_create and the terrain, path and scatter steps: land had no tool at all; ADR 0077 by 450
                // for the roof, trim, decorate and weather steps, one line each, their details in guide=build; ADR 0079
                // for the roof, trim, decorate and weather steps, one line each, their details in guide=build; ADR 0079
                // by 300 for tree, forest and flora, the same way; ADR 0080 by 200 for the seven shaping steps;
                // ADR 0081 by 900 for pack_asset (every pack file but textures had no tool) and guide pages,
                // its formats in guide=pack; ADR 0082 by 350 for the block entity, entity, biome, feature and light steps;
                // ADR 0083 by 200 for furnish and image.
                assertThat(listed.toString().length()).isLessThan(20200);
                JsonObject batch = tools.get(names.indexOf("build_batch")).getAsJsonObject().getAsJsonObject("inputSchema");
                assertThat(batch.getAsJsonArray("required").toString()).isEqualTo("[\"steps\"]");
                // Every project template the jar ships (paper-server is only nested in others) is named in project_scaffold's schema, so none goes unseen.
                String templates = tools.get(names.indexOf("project_scaffold")).getAsJsonObject()
                        .getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("template")
                        .get("description").getAsString();
                assertThat(templates).contains(io.github.imnotstable.msc.templates.Templates.ids().stream()
                        .filter(id -> !id.equals(io.github.imnotstable.msc.templates.Templates.PAPER_SERVER)).toList());

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

    @Test
    void badOrBatchedLinesDoNotStopTheServer(@TempDir Path workspace) throws Exception {
        java.io.ByteArrayOutputStream noteBytes = new java.io.ByteArrayOutputStream();
        try (CoreHarness h = new CoreHarness(workspace);
             PipedOutputStream toServer = new PipedOutputStream();
             PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 16);
             PipedOutputStream serverOut = new PipedOutputStream();
             PipedInputStream fromServer = new PipedInputStream(serverOut, 1 << 20);
             java.io.PrintStream notes = new java.io.PrintStream(noteBytes, true, StandardCharsets.UTF_8)) {
            McpSyncServer server = McpBridge.start(h.core.registry(), serverIn, serverOut, notes);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8));
            try {
                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                read(reader, 1);
                send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                send(toServer, "this is not json");
                send(toServer, "{\"hello\":1}");
                send(toServer, "[{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"},42]");
                assertThat(read(reader, 2).has("result")).isTrue();
                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
                assertThat(read(reader, 3).has("result")).isTrue();
                assertThat(noteBytes.toString(StandardCharsets.UTF_8).lines().toList()).containsExactly(
                        "msc-core: ignored stdin input that is not a JSON-RPC message: this is not json",
                        "msc-core: ignored stdin input that is not a JSON-RPC message: {\"hello\":1}",
                        "msc-core: ignored stdin input that is not a JSON-RPC message: 42");
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
