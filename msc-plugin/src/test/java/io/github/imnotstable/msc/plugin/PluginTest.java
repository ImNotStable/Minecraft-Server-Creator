package io.github.imnotstable.msc.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The plugin's files as Claude Code reads them, and the launcher starting the real core over MCP stdio. */
class PluginTest {
    private static final Path PLUGIN = Path.of(System.getProperty("msc.pluginDir"));
    private static final Path CORE_INSTALL = Path.of(System.getProperty("msc.coreInstall"));
    private static final String ROOT_VAR = "${CLAUDE_PLUGIN_ROOT}/";

    @TempDir
    Path workspace;

    @Test
    void manifestAndMarketplaceAgree() throws IOException {
        JsonObject plugin = json(PLUGIN.resolve(".claude-plugin/plugin.json"));
        JsonObject market = json(PLUGIN.getParent().resolve(".claude-plugin/marketplace.json"));
        JsonObject entry = market.getAsJsonArray("plugins").get(0).getAsJsonObject();

        assertThat(plugin.get("name").getAsString()).isEqualTo("msc").isEqualTo(entry.get("name").getAsString());
        assertThat(PLUGIN.getParent().resolve(entry.get("source").getAsString()).normalize()).isEqualTo(PLUGIN);
    }

    @Test
    void mcpConfigRunsTheLauncherSourceWithJava() throws IOException {
        JsonObject server = json(PLUGIN.resolve(".mcp.json")).getAsJsonObject("mcpServers").getAsJsonObject("msc");
        String arg = server.getAsJsonArray("args").get(0).getAsString();

        assertThat(server.get("command").getAsString()).isEqualTo("java");
        assertThat(arg).startsWith(ROOT_VAR);
        assertThat(PLUGIN.resolve(arg.substring(ROOT_VAR.length()))).isRegularFile();
    }

    @Test
    void launcherRunsTheCoreInItsOwnJvm() throws Exception {
        assertThat(initialize(List.of())).contains("\"serverInfo\"");
    }

    @Test
    void launcherRunsTheCoreInAChildWithAnotherJava() throws Exception {
        String java = MscLauncher.executable(Path.of(System.getProperty("java.home"))).toString();
        assertThat(initialize(List.of(MscLauncher.ENV_JAVA + "=" + java))).contains("\"serverInfo\"");
    }

    /** The core starts child JVMs (the standalone pack host) with its own class path, so in-process it must be set. */
    @Test
    void inProcessCoreSeesItsJarsAsTheClassPath() throws Exception {
        Path install = workspace.resolve("home/msc-core-1");
        Path lib = Files.createDirectories(install.resolve("lib"));
        Path src = Files.createDirectories(workspace.resolve("src/io/github/imnotstable/msc/core")).resolve("Main.java");
        Files.writeString(src, "package io.github.imnotstable.msc.core; public class Main { public static void "
                + "main(String[] a) { System.out.println(System.getProperty(\"java.class.path\")); } }");
        Path classes = workspace.resolve("classes");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, OutputStream.nullOutputStream(),
                "-d", classes.toString(), src.toString())).isZero();
        Path jar = lib.resolve("msc-core-1.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("io/github/imnotstable/msc/core/Main.class"));
            out.write(Files.readAllBytes(classes.resolve("io/github/imnotstable/msc/core/Main.class")));
        }
        Files.createFile(lib.resolve("dep.jar"));

        Process p = launcher(install.getParent(), List.of()).start();
        String line = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();

        assertThat(line).isEqualTo(lib.resolve("dep.jar") + File.pathSeparator + jar);
    }

    /** Starts the plugin's command with the core's distribution as the only install; answers MCP initialize. */
    private String initialize(List<String> env) throws Exception {
        Process p = launcher(CORE_INSTALL, env).start();
        try {
            OutputStream in = p.getOutputStream();
            in.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}\n")
                    .getBytes(StandardCharsets.UTF_8));
            in.flush();
            String line = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
            in.close();
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("exits when stdin closes").isTrue();
            return String.valueOf(line);
        } finally {
            p.destroyForcibly();
        }
    }

    /** The plugin's command, run in the workspace with {@code home} as the folder that holds the installs. */
    private ProcessBuilder launcher(Path home, List<String> env) {
        List<String> cmd = new ArrayList<>(List.of(
                MscLauncher.executable(Path.of(System.getProperty("java.home"))).toString(),
                PLUGIN.resolve("launcher/io/github/imnotstable/msc/plugin/MscLauncher.java").toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workspace.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put(MscLauncher.ENV_HOME, home.toString());
        pb.environment().remove(MscLauncher.ENV_JAVA);
        for (String e : env) {
            pb.environment().put(e.substring(0, e.indexOf('=')), e.substring(e.indexOf('=') + 1));
        }
        return pb;
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
}
