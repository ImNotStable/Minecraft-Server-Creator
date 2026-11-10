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
import java.util.List;
import java.util.Map;

/** The only class that touches the MCP SDK: exposes the tool registry over stdio. */
public final class McpBridge {
    private static final Gson GSON = new Gson();
    private static final String INSTRUCTIONS = "Tools for developing a Minecraft server. Typical loop: "
            + "project_scaffold, server_setup, then edit code and call dev_cycle. Look up API names with api_search "
            + "before guessing. See the world with world_summary and world_slice; build with build_structure. "
            + "Results are size-capped; narrow the request rather than raising maxChars.";

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
                return McpSchema.CallToolResult.builder().addTextContent(result.text()).isError(!result.ok()).build();
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
