package io.github.imnotstable.msc.core.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.imnotstable.msc.core.Core;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** The only class that touches the MCP SDK: exposes the tool registry over stdio. */
public final class McpBridge {
    private static final Gson GSON = new Gson();
    private static final String INSTRUCTIONS = "Tools for developing a Minecraft server (Paper, Fabric or Minestom). "
            + "New project: project_scaffold into the workspace (template paper-game for a minigame: kit, stages, saved "
            + "progress and /game status and stage-jump commands), then read its CLAUDE.md for conventions. Paper and "
            + "Fabric need the user's agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA); server_setup says "
            + "if this machine already has it. If not, ask them, then server_setup acceptEula=true; never accept it "
            + "for them. Then edit code and call dev_cycle after each "
            + "change; it builds, runs tests, deploys, restarts and reports errors only. Look up API names with "
            + "api_search before guessing. Dev worlds are superflat: server_start and dev_cycle say where the ground "
            + "is. See the world with world_summary, world_slice or world_render. Build with build_structure for "
            + "irregular shapes, build_shape for circles, spheres and lines, build_stamp to repeat or turn a structure, "
            + "and build_batch to send many steps as one edit; blocks take weighted mixes such as grass_block:8,moss_block:2. "
            + "Testing a game: server_setup mode=game (survival), world_entities to check mobs and NPCs, server_wait "
            + "until=<regex> instead of polling, server_command \"tick sprint 600\" to skip ahead; chunks with no "
            + "player nearby do not tick, so keep game areas loaded with chunk tickets or forceload. "
            + "Results are size-capped; narrow the request rather than raising maxChars. Wrong folder: "
            + "msc_workspace switches it without a restart.";

    private McpBridge() {
    }

    /** Start serving on the given streams. The returned server runs until closed or the input ends. */
    public static McpSyncServer start(ToolRegistry registry, InputStream in, OutputStream out) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (Tool tool : registry.tools()) {
            Map<String, Object> schema = GSON.fromJson(tool.inputSchema(), new TypeToken<Map<String, Object>>() {
            }.getType());
            McpSchema.Tool definition = McpSchema.Tool.builder(tool.name(), schema)
                    .description(tool.description()).build();
            specs.add(new McpServerFeatures.SyncToolSpecification(definition, (exchange, request) -> {
                JsonObject args = request.arguments() == null ? new JsonObject()
                        : GSON.toJsonTree(request.arguments()).getAsJsonObject();
                ToolRegistry.Result result = registry.call(tool.name(), args);
                McpSchema.CallToolResult.Builder reply = McpSchema.CallToolResult.builder();
                if (result.png() != null) {
                    reply.addContent(McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(result.png()),
                            "image/png").build());
                }
                return reply.addTextContent(result.text()).isError(!result.ok()).build();
            }));
        }
        return McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out))
                .serverInfo("msc-core", Core.VERSION)
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
    }
}
