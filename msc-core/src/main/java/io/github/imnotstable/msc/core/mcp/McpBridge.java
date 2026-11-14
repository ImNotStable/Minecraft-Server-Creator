package io.github.imnotstable.msc.core.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.imnotstable.msc.core.Core;
import io.github.imnotstable.msc.core.server.Platform;
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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** The only class that touches the MCP SDK: exposes the tool registry over stdio. */
public final class McpBridge {
    private static final Gson GSON = new Gson();
    /** Arguments as the client sent them: a number no double holds (1e400) arrives as Infinity and must not throw here. */
    private static final Gson ARGS = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create();
    private static final String INSTRUCTIONS = "Tools for developing a Minecraft server ("
            + Platform.names(p -> true, "or") + "). "
            + "Start: project_scaffold (template <platform>-game for a minigame base), then read the project's CLAUDE.md. "
            + Platform.names(Platform::needsEula, "and") + " need the user's agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA): if "
            + "server_setup says this machine lacks it, ask them, then server_setup acceptEula=true; never accept it for them. After each code change call "
            + "dev_cycle (build, test, deploy, restart, errors only). Look names up with api_search before guessing. "
            + "Dev worlds are superflat; server_start and dev_cycle say where the ground is. See the world with "
            + "world_summary (also finds rare blocks), world_slice or world_render; build with build_batch, a whole build per "
            + "call; server_command runs several commands, one per line. Textures: texture kind=gem (or ingot, orb, sword...) "
            + "draws a finished one in one call, a .png name writes a plain file; pack makes the zip with the right "
            + "pack.mcmeta (file= saves a copy). "
            + "Testing a game: server_setup mode=game, player_test to play it, world_entities, server_wait instead "
            + "of polling, server_command \"tick sprint 600\" to skip ahead. Chunks with no player nearby do not "
            + "tick: keep game areas loaded with forceload. Results are size-capped: narrow the request rather than "
            + "raising maxChars. Wrong folder: msc_workspace.";

    private McpBridge() {
    }

    /** Start serving on the given streams. The returned server runs until closed or the input ends. */
    public static McpSyncServer start(ToolRegistry registry, InputStream in, OutputStream out) {
        return start(registry, in, out, System.err);
    }

    /** Same, with notes about dropped input lines going to {@code notes} instead of stderr. */
    public static McpSyncServer start(ToolRegistry registry, InputStream in, OutputStream out, PrintStream notes) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (Tool tool : registry.tools()) {
            Map<String, Object> schema = GSON.fromJson(tool.inputSchema(), new TypeToken<Map<String, Object>>() {
            }.getType());
            McpSchema.Tool definition = McpSchema.Tool.builder(tool.name(), schema)
                    .description(tool.description()).build();
            specs.add(new McpServerFeatures.SyncToolSpecification(definition, (exchange, request) -> {
                JsonObject args = request.arguments() == null ? new JsonObject()
                        : ARGS.toJsonTree(request.arguments()).getAsJsonObject();
                ToolRegistry.Result result = registry.call(tool.name(), args);
                McpSchema.CallToolResult.Builder reply = McpSchema.CallToolResult.builder();
                if (result.png() != null) {
                    reply.addContent(McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(result.png()),
                            "image/png").build());
                }
                return reply.addTextContent(result.text()).isError(!result.ok()).build();
            }));
        }
        return McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper(),
                        // The SDK stops reading for good at a line it cannot parse (#283).
                        new JsonRpcLines(in, notes), out))
                .serverInfo("msc-core", Core.VERSION)
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // Off, so every call reaches ToolRegistry.call, which checks the arguments (ArgCheck), answers in MSC's
                // one-line "error: ..." form and logs the call; the SDK's check would answer for it, unlogged (#510).
                .validateToolInputs(false)
                .tools(specs)
                .build();
    }
}
