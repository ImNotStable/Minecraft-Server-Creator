package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for Claude Code in runner tests: writes the prompt (its first argument, else stdin) to {@code done.txt}, logs one tool
 * call when {@code MSC_TOOL_LOG} is set, leaves the file {@code FAKE_LEAVE} names, and prints a Claude-style JSON result (a usage-limit error when
 * {@code FAKE_USAGE_LIMIT} is set, nothing when {@code FAKE_SILENT} is set). With {@code FAKE_HANG} set it starts a
 * child that sleeps (as Claude Code starts msc-core) and sleeps itself, like an agent still at work.
 */
public final class FakeAgent {

    private FakeAgent() {
    }

    /** First argument of the sleeping child {@code FAKE_HANG} starts. */
    public static final String SLEEP = "--sleep";

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 0 && args[0].equals(SLEEP)) {
            Thread.sleep(Long.MAX_VALUE);
        }
        // The prompt: the first argument, or stdin when there is none, as claude -p reads it (#624).
        String prompt = args.length > 0 ? args[0] : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("done.txt"), prompt);
        // FAKE_LEAVE "path=text": state the agent's own testing left in the workspace, such as a saved plugin config.
        String leave = System.getenv("FAKE_LEAVE");
        if (leave != null) {
            Path file = Path.of(leave.substring(0, leave.indexOf('=')));
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, leave.substring(leave.indexOf('=') + 1));
        }
        // FAKE_LINK "link=target": a symbolic link the agent made in the workspace, such as one out of it (#639).
        String link = System.getenv("FAKE_LINK");
        if (link != null) {
            Files.createSymbolicLink(Path.of(link.substring(0, link.indexOf('='))),
                    Path.of(link.substring(link.indexOf('=') + 1)));
        }
        // FAKE_LEAVE_SERVER "port": a server the agent started in the workspace and left running when it exited, as
        // a detached Minestom server (#577). It names the workspace on its command line, as a jar path there does.
        String leaveServer = System.getenv("FAKE_LEAVE_SERVER");
        if (leaveServer != null) {
            Path workspace = Path.of("").toAbsolutePath();
            PortHolder.start(workspace, Integer.parseInt(leaveServer), workspace.resolve("left-server.port"),
                    workspace.resolve("build/libs/game.jar").toString());
        }
        if (System.getenv("FAKE_HANG") != null) {
            // The marker comes first: the OS cuts long command lines.
            new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
                    "-Dmsc.fakeAgent=" + System.getProperty("msc.fakeAgent", ""), "-cp", System.getProperty("java.class.path"), FakeAgent.class.getName(), SLEEP)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
            Thread.sleep(Long.MAX_VALUE);
        }
        String toolLog = System.getenv("MSC_TOOL_LOG");
        if (toolLog != null) {
            Files.writeString(Path.of(toolLog),
                    "{\"v\":1,\"tool\":\"world.fill\",\"ok\":true,\"durationMs\":5,\"resultChars\":40,\"resultTokens\":10}\n");
        }
        // FAKE_MCP_COUNTER: count runs in that file and report the msc server connected from the second run on.
        String counter = System.getenv("FAKE_MCP_COUNTER");
        String servers = "[]";
        if (counter != null) {
            Path file = Path.of(counter);
            int runs = (Files.exists(file) ? Integer.parseInt(Files.readString(file).strip()) : 0) + 1;
            Files.writeString(file, Integer.toString(runs));
            servers = runs >= 2 ? "[{\"name\":\"msc\",\"status\":\"connected\"}]" : "[]";
        }
        if (System.getenv("FAKE_USAGE_LIMIT") != null) {
            // UTF-8 like Claude Code, whatever the platform's console charset is.
            new PrintStream(System.out, true, StandardCharsets.UTF_8).println("[{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":1,"
                    + "\"result\":\"API Error: 429 You're out of extra usage · resets 2:50am\"}]");
            return;
        }
        if (System.getenv("FAKE_SILENT") != null) {
            // Like Claude Code killed at the time limit or crashing at start: no JSON on stdout.
            return;
        }
        int output = toolLog != null ? 100 : 300;
        System.out.println("[{\"type\":\"system\",\"subtype\":\"init\",\"mcp_servers\":" + servers + "},");
        System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":4,"
                + "\"duration_api_ms\":1200,\"total_cost_usd\":0.05,\"usage\":{\"input_tokens\":1000,"
                + "\"cache_creation_input_tokens\":200,\"cache_read_input_tokens\":3000,\"output_tokens\":" + output + "}}]");
    }
}
