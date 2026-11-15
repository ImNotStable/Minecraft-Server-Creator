package io.github.imnotstable.msc.core;

import io.github.imnotstable.msc.core.server.DownloadStore;
import io.github.imnotstable.msc.core.server.GradleUserHome;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.tool.PathArg;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.transport.HandshakeProof;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the core reads from its environment. Tests build one by hand.
 *
 * @param workspace     the project the AI is working in
 * @param serverDir     where the dev server lives
 * @param agentPort     loopback port agents connect to; 0 picks a free one
 * @param token         shared secret agents must present
 * @param toolLog       JSON Lines file for tool calls, or null for none
 * @param javaCommand   java executable used to run the game server
 * @param serverCommand full command that starts the server, or null for {@code java -jar <paper jar> --nogui}
 *                      ({@code ServerJava.processBuilder} adds the UTF-8 console flags when its program is
 *                      {@code java}, else puts them in {@code JAVA_OPTS}; a script that ignores JAVA_OPTS must pass
 *                      {@code ServerJava.ENCODING_FLAGS} to its Java itself, or non-ASCII output arrives garbled)
 * @param buildCommand  full command that builds a project, or null for its Gradle wrapper
 * @param downloadsUrl  base URL of the Paper downloads service
 * @param minecraft     Minecraft version used when a tool is not given one
 * @param apiJars       jars to index for API lookups; empty means find paper-api in the Gradle cache
 * @param packHost      interface the resource pack server binds and advertises
 * @param packFormat    resource pack format number written to pack.mcmeta
 * @param agentJar      the msc agent plugin or mod to install into the server, or null to look in {@code .msc/agent}
 * @param fabricMetaUrl base URL of Fabric's meta service (server launcher downloads)
 * @param fabricMavenUrl base URL of Fabric's Maven repository (Fabric API download)
 * @param installDir    where this core is installed, or null when unknown; its {@code agents/} folder holds the
 *                      agent jars shipped with the core, the last place {@code server_setup} looks
 * @param packPort      port the resource pack server binds; 0 reuses the port this workspace used last
 * @param machineEula   the owner's one-time, per-machine EULA acceptance ({@code ~/.msc/eula.json}); read by
 *                      {@code server_setup} and {@code server_start}, written only on the owner's explicit opt-in
 *                      (see {@link MachineEula}); {@link MachineEula#none} (tests) means there is none
 * @param loaderUrls    where the NeoForge, Forge and Quilt installers and the Purpur jars are downloaded from
 * @param downloadStore the machine's store of checksummed server jars ({@code ~/.msc/downloads}), shared by every
 *                      workspace (ADR 0038); {@link DownloadStore#NONE} (tests) means there is none
 * @param proxySecret   the machine's Velocity forwarding secret file ({@code ~/.msc/velocity.secret}), shared by a
 *                      proxy workspace and its backend's (ADR 0045); null (tests) keeps it in {@link #dataDir()}
 * @param gradleUserHome where Gradle keeps its caches ({@link GradleUserHome}): passed to every Gradle build the core
 *                      starts, and where api_search finds the API jars those builds downloaded (#666)
 */
public record CoreConfig(Path workspace, Path serverDir, int agentPort, String token, Path toolLog,
                         String javaCommand, List<String> serverCommand, List<String> buildCommand,
                         String downloadsUrl, String minecraft, List<Path> apiJars, String packHost,
                         int packFormat, Path agentJar, String fabricMetaUrl, String fabricMavenUrl,
                         Path installDir, int packPort, MachineEula machineEula,
                         LoaderInstaller.Urls loaderUrls, DownloadStore downloadStore, Path proxySecret,
                         Path gradleUserHome) {
    public static final String ENV_WORKSPACE = "MSC_WORKSPACE";
    public static final String ENV_SERVER_DIR = "MSC_SERVER_DIR";
    public static final String ENV_TOOL_LOG = "MSC_TOOL_LOG";
    public static final String ENV_JAVA = "MSC_JAVA";
    public static final String ENV_DOWNLOADS_URL = "MSC_DOWNLOADS_URL";
    public static final String ENV_MINECRAFT = "MSC_MINECRAFT";
    public static final String ENV_API_JARS = "MSC_API_JARS";
    public static final String ENV_PACK_HOST = "MSC_PACK_HOST";
    public static final String ENV_PACK_FORMAT = "MSC_PACK_FORMAT";
    public static final String ENV_AGENT_JAR = "MSC_AGENT_JAR";
    public static final String ENV_PACK_PORT = "MSC_PACK_PORT";

    public static final String ENV_FABRIC_META_URL = "MSC_FABRIC_META_URL";
    public static final String ENV_FABRIC_MAVEN_URL = "MSC_FABRIC_MAVEN_URL";

    public static final String DEFAULT_FABRIC_META_URL = "https://meta.fabricmc.net";
    public static final String DEFAULT_FABRIC_MAVEN_URL = "https://maven.fabricmc.net";
    public static final String DEFAULT_DOWNLOADS_URL = "https://fill.papermc.io";
    public static final String DEFAULT_MINECRAFT = "26.3";
    /** Lowest resource pack format the pack claims: 97 is 26.3 (pack_version.resource_major 97, minor 1). */
    public static final int DEFAULT_PACK_FORMAT = 97;

    public CoreConfig {
        workspace = workspace.toAbsolutePath().normalize();
        serverDir = serverDir.toAbsolutePath().normalize();
        apiJars = List.copyOf(apiJars);
        machineEula = Objects.requireNonNull(machineEula, "machineEula: MachineEula.none() for none");
        downloadStore = Objects.requireNonNull(downloadStore, "downloadStore: DownloadStore.NONE for none");
        gradleUserHome = gradleUserHome.toAbsolutePath().normalize();
    }

    /** Defaults for a workspace; used by tests and as the base for {@link #fromEnvironment}. */
    public static CoreConfig forWorkspace(Path workspace) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new CoreConfig(workspace, workspace.resolve("run"), 0, HandshakeProof.nonce(), null, java,
                null, null, DEFAULT_DOWNLOADS_URL, DEFAULT_MINECRAFT, List.of(), "127.0.0.1", DEFAULT_PACK_FORMAT, null,
                DEFAULT_FABRIC_META_URL, DEFAULT_FABRIC_MAVEN_URL, null, 0, MachineEula.none(), LoaderInstaller.Urls.DEFAULT,
                DownloadStore.NONE, null, GradleUserHome.ofThisProcess());
    }

    public static CoreConfig fromEnvironment(Map<String, String> env) {
        Path workspace = Path.of(env.getOrDefault(ENV_WORKSPACE, "."));
        CoreConfig base = forWorkspace(workspace);
        int port = 0;
        String address = env.get(Limits.ENV_CORE_ADDRESS);
        if (address != null && address.contains(":")) {
            port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1).trim());
        }
        String logFile = env.get(ENV_TOOL_LOG);
        List<Path> jars = env.containsKey(ENV_API_JARS)
                ? List.of(env.get(ENV_API_JARS).split(java.io.File.pathSeparator)).stream().map(Path::of).toList()
                : List.of();
        return new CoreConfig(
                workspace,
                env.containsKey(ENV_SERVER_DIR) ? Path.of(env.get(ENV_SERVER_DIR)) : base.serverDir(),
                port,
                token(env, base.token()),
                logFile != null ? Path.of(logFile) : base.dataDir().resolve("tool-calls.jsonl"),
                env.getOrDefault(ENV_JAVA, base.javaCommand()),
                null, null,
                env.getOrDefault(ENV_DOWNLOADS_URL, DEFAULT_DOWNLOADS_URL),
                env.getOrDefault(ENV_MINECRAFT, DEFAULT_MINECRAFT),
                jars,
                env.getOrDefault(ENV_PACK_HOST, "127.0.0.1"),
                Integer.parseInt(env.getOrDefault(ENV_PACK_FORMAT, String.valueOf(DEFAULT_PACK_FORMAT))),
                env.containsKey(ENV_AGENT_JAR) ? Path.of(env.get(ENV_AGENT_JAR)) : null,
                env.getOrDefault(ENV_FABRIC_META_URL, DEFAULT_FABRIC_META_URL),
                env.getOrDefault(ENV_FABRIC_MAVEN_URL, DEFAULT_FABRIC_MAVEN_URL),
                null,
                Integer.parseInt(env.getOrDefault(ENV_PACK_PORT, "0").trim()),
                MachineEula.forHome(Path.of(System.getProperty("user.home"))),
                base.loaderUrls(),
                DownloadStore.fromEnvironment(env, Path.of(System.getProperty("user.home"))),
                Path.of(System.getProperty("user.home"), ".msc", "velocity.secret"),
                GradleUserHome.resolve(env, System.getProperty(GradleUserHome.PROPERTY),
                        System.getProperty("user.home")));
    }

    /** {@code MSC_TOKEN} when set and not blank (an empty one would let any agent prove itself), else a random one. */
    static String token(Map<String, String> env, String random) {
        String token = env.get(Limits.ENV_TOKEN);
        return token == null || token.isBlank() ? random : token;
    }

    /** {@code <workspace>/.msc}: caches, the resource pack source and logs. */
    public Path dataDir() {
        return workspace.resolve(".msc");
    }

    /** {@code .msc/cache}: downloaded server jars, installers and loader files. */
    public Path cacheDir() {
        return dataDir().resolve("cache");
    }

    /** {@code .msc/pack}: the resource pack's sources. */
    public Path packDir() {
        return dataDir().resolve("pack");
    }

    /** {@code .msc/build}: the built pack zip; the pack host's port, pid and log files sit beside it. */
    public Path packBuildDir() {
        return dataDir().resolve("build");
    }

    /** {@code .msc/backups}: world backups taken before big edits and on request. */
    public Path backupsDir() {
        return dataDir().resolve("backups");
    }

    /**
     * Everything MSC itself creates in a workspace, each where the code that creates it finds it: {@code .msc} (the
     * caches, the pack's sources and build, backups, the logs), the server folder and the tool log. Absolute and
     * normalized; some may be outside the workspace (an MSC_SERVER_DIR or MSC_TOOL_LOG elsewhere). A folder that holds
     * only these is still empty for {@code project_scaffold} (#664).
     */
    public List<Path> ownPaths() {
        List<Path> own = new ArrayList<>(List.of(dataDir(), cacheDir(), packDir(), packBuildDir(),
                backupsDir(), serverDir, proxySecretFile()));
        if (toolLog != null) {
            own.add(toolLog);
        }
        return own.stream().map(p -> p.toAbsolutePath().normalize()).toList();
    }

    public CoreConfig withServerCommand(List<String> command) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, command, buildCommand,
                downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withBuildCommand(List<String> command) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand, command,
                downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withToolLog(Path file) {
        return new CoreConfig(workspace, serverDir, agentPort, token, file, javaCommand, serverCommand, buildCommand,
                downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withDownloadsUrl(String url) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, url, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withFabricUrls(String metaUrl, String mavenUrl) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, metaUrl, mavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withMinecraft(String version) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, version, apiJars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withApiJars(List<Path> jars) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, jars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withInstallDir(Path dir) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl,
                fabricMavenUrl, dir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    /**
     * The same settings for another workspace. The server folder and tool log move along when they were the
     * defaults inside the old workspace; a folder set explicitly (MSC_SERVER_DIR, MSC_TOOL_LOG) stays.
     */
    public CoreConfig withWorkspace(Path dir) {
        Path ws = dir.toAbsolutePath().normalize();
        Path server = serverDir.equals(workspace.resolve("run")) ? ws.resolve("run") : serverDir;
        Path logFile = toolLog != null && toolLog.toAbsolutePath().normalize().startsWith(dataDir())
                ? ws.resolve(".msc").resolve(dataDir().relativize(toolLog.toAbsolutePath().normalize()))
                : toolLog;
        return new CoreConfig(ws, server, agentPort, token, logFile, javaCommand, serverCommand, buildCommand,
                downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl, fabricMavenUrl,
                installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withMachineEula(MachineEula eula) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl,
                fabricMavenUrl, installDir, packPort, eula, loaderUrls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withDownloadStore(DownloadStore store) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl,
                fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, store, proxySecret, gradleUserHome);
    }

    public CoreConfig withLoaderUrls(LoaderInstaller.Urls urls) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl,
                fabricMavenUrl, installDir, packPort, machineEula, urls, downloadStore, proxySecret, gradleUserHome);
    }

    public CoreConfig withProxySecret(Path file) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl,
                fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, file, gradleUserHome);
    }

    public CoreConfig withGradleUserHome(Path dir) {
        return new CoreConfig(workspace, serverDir, agentPort, token, toolLog, javaCommand, serverCommand,
                buildCommand, downloadsUrl, minecraft, apiJars, packHost, packFormat, agentJar, fabricMetaUrl,
                fabricMavenUrl, installDir, packPort, machineEula, loaderUrls, downloadStore, proxySecret, dir);
    }

    /** Where the Velocity forwarding secret is kept: {@link #proxySecret()}, else the workspace's {@code .msc}. */
    public Path proxySecretFile() {
        return proxySecret != null ? proxySecret : dataDir().resolve("velocity.secret");
    }

    /** The rule for a path argument: relative to the workspace and inside it (see {@link PathArg}). */
    public PathArg inWorkspace() {
        return PathArg.inside(workspace);
    }
}
