package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.backup.WorldFolders;
import io.github.imnotstable.msc.core.server.Network;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ProxyForwarding;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code server_export} (ADR 0091): the dev server as something to run in production. {@code docker}: a Compose
 * project on the itzg images (the server or proxy image downloads and updates the server jar itself, plugins from
 * Modrinth and SpigotMC stay up to date, and an mc-backup service backs the world up on a schedule), with each backend
 * of a proxy network as its own service. {@code pterodactyl}: an egg whose install script downloads the server, and a
 * zip of the server's files to upload. Dev-only things stay behind: the msc agent, the server jar, logs, caches, the
 * pack host's local URL, and the world unless asked for.
 */
final class ServerExport {
    /** Files and folders of the server folder that are the dev setup, not the server to ship. */
    private static final Set<String> DEV_ONLY = Set.of("logs", "crash-reports", "cache", "libraries", "versions",
            "bundler", "debug", ".paper-remapped", "modules", "eula.txt", "usercache.json", "servers", "backups",
            "server.jar", "fabric-server-launch.jar", "quilt-server-launch.jar", "forge-server.jar",
            PluginTools.VERSION_FILE, PluginTools.SOURCES_FILE, "bStats");
    private static final String ITZG_SERVER = "itzg/minecraft-server:java25";
    private static final String ITZG_PROXY = "itzg/mc-proxy:java25";
    private static final String ITZG_BACKUP = "itzg/mc-backup";
    private static final String YOLK = "ghcr.io/pterodactyl/yolks:java_25";

    private final CoreConfig config;
    private final ServerManager server;

    ServerExport(CoreConfig config, ServerManager server) {
        this.config = config;
        this.server = server;
    }

    String run(Args args) throws IOException {
        String target = args.str("target", "docker").strip().toLowerCase(Locale.ROOT);
        if (!List.of("docker", "pterodactyl").contains(target)) {
            throw new ToolException("target must be docker or pterodactyl, not \"" + target + "\"");
        }
        boolean world = args.bool("world", false);
        Platform platform = server.platform();
        Path run = config.serverDir();
        if (!Files.isDirectory(run)) {
            throw new ToolException("no server in " + run + ": server_setup first");
        }
        if (world && server.running()) {
            throw new ToolException("stop the server before exporting its world, so the copy is whole");
        }
        if (target.equals("pterodactyl") && !Network.members(run).isEmpty()) {
            throw new ToolException("a proxy network is several servers: target=docker ships it as one Compose "
                    + "project; on Pterodactyl make one server per backend");
        }
        Path out = OutputFile.checkFolder(args.path("dir", "export/" + target, config.inWorkspace()), "dir");
        if (Files.isDirectory(out)) {
            try (Stream<Path> s = Files.list(out)) {
                if (s.findAny().isPresent()) {
                    throw new ToolException(out + " is not empty: export into a new folder (dir=)");
                }
            }
        }
        Files.createDirectories(out);
        java.util.SequencedSet<String> notes = new java.util.LinkedHashSet<>();
        String result = target.equals("docker") ? docker(platform, run, out, world, notes)
                : pterodactyl(platform, run, out, world, notes);
        return result + (notes.isEmpty() ? "" : "\nnotes:\n- " + String.join("\n- ", notes));
    }

    // ---- what is shipped ----------------------------------------------------------------------------------------

    /** The Minecraft version the server runs: what server_setup installed, else the core's. */
    private String minecraft(Path run) throws IOException {
        Path file = run.resolve(PluginTools.VERSION_FILE);
        return Files.exists(file) ? Files.readString(file).strip() : config.minecraft();
    }

    /**
     * Copies {@code run} into {@code data} without the dev-only files, the msc agent, jars an update source keeps
     * (named in {@code managed}), the platform's server jar and, unless {@code world}, the world folders; adjusts
     * {@code server.properties} for production. Returns the plugin jars it copied.
     */
    private List<String> copy(Platform platform, Path run, Path data, boolean world, Set<String> managed,
                              boolean behindProxy, java.util.SequencedSet<String> notes) throws IOException {
        Files.createDirectories(data);
        List<String> worlds = WorldFolders.in(run);
        List<String> jars = new ArrayList<>();
        try (Stream<Path> children = Files.list(run)) {
            for (Path p : children.sorted().toList()) {
                String name = p.getFileName().toString();
                if (DEV_ONLY.contains(name) || name.equals(platform.serverJar()) || name.startsWith(".msc")
                        || name.startsWith("hs_err_pid") || name.endsWith(".log")
                        || (!world && worlds.contains(name))) {
                    continue;
                }
                copyTree(p, data.resolve(name), platform, managed, jars);
            }
        }
        if (!world && !worlds.isEmpty()) {
            notes.add("the world (" + String.join(", ", worlds) + ") stays behind: world=true ships it (server "
                    + "stopped)");
        }
        // Geyser bound like the dev server: in a container it listens on all addresses.
        for (String flavor : List.of("Geyser-Spigot", "Geyser-Velocity", "Geyser-BungeeCord")) {
            Path geyser = data.resolve("plugins").resolve(flavor).resolve("config.yml");
            if (Files.exists(geyser)) {
                Files.writeString(geyser, Files.readString(geyser).replaceAll("(?m)^(\\s+address:) 127\\.0\\.0\\.1$",
                        "$1 0.0.0.0"));
            }
        }
        Path props = data.resolve(ServerPropertiesFile.NAME);
        if (Files.exists(props)) {
            Map<String, String> p = ServerPropertiesFile.read(props);
            Map<String, String> set = new LinkedHashMap<>();
            set.put("server-ip", "");
            set.put("server-port", "25565");
            if (!behindProxy) {
                set.put("online-mode", "true");
            }
            String pack = p.getOrDefault("resource-pack", "");
            if (pack.contains("127.0.0.1") || pack.contains("localhost")) {
                set.put("resource-pack", "");
                set.put("resource-pack-sha1", "");
                notes.add("resource-pack pointed at MSC's local pack host: host the pack zip (pack file=) somewhere "
                        + "public and set resource-pack and resource-pack-sha1 in " + ServerPropertiesFile.NAME);
            }
            ServerPropertiesFile.set(props, set);
        }
        return jars;
    }

    private void copyTree(Path from, Path to, Platform platform, Set<String> managed, List<String> jars)
            throws IOException {
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (Stream<Path> children = Files.list(from)) {
                for (Path c : children.sorted().toList()) {
                    copyTree(c, to.resolve(c.getFileName().toString()), platform, managed, jars);
                }
            }
            return;
        }
        String name = from.getFileName().toString();
        boolean addon = platform.addonDir() != null && from.getParent().getFileName() != null
                && from.getParent().getFileName().toString().equals(platform.addonDir()) && name.endsWith(".jar");
        if (addon && (name.startsWith("msc-agent-") || managed.contains(name))) {
            return;
        }
        Files.copy(from, to);
        if (addon) {
            jars.add(name);
        }
    }

    /** Recorded plugin sources the server image can update itself from: Modrinth slugs and SpigotMC numbers. */
    private Map<String, List<String>> updatable(Path run, Set<String> managed) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>(Map.of("modrinth", new ArrayList<>(),
                "spigot", new ArrayList<>()));
        for (Map.Entry<String, String> e : PluginTools.sources(run).entrySet()) {
            String source = e.getValue().substring(0, e.getValue().indexOf(':'));
            if (out.containsKey(source) && Files.exists(run.resolve(server.platform().addonDir() == null ? "plugins"
                    : server.platform().addonDir()).resolve(e.getKey()))) {
                out.get(source).add(e.getValue().substring(source.length() + 1));
                managed.add(e.getKey());
            }
        }
        return out;
    }

    private static boolean geyser(Path run) {
        return Files.exists(run.resolve("plugins/Geyser-Spigot.jar")) || Files.exists(run.resolve(
                "plugins/Geyser-Velocity.jar")) || Files.exists(run.resolve("plugins/Geyser-BungeeCord.jar"));
    }

    // ---- docker ---------------------------------------------------------------------------------------------------

    private String docker(Platform platform, Path run, Path out, boolean world, java.util.SequencedSet<String> notes) throws IOException {
        String rcon = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
        StringBuilder compose = new StringBuilder("# Made by Minecraft Server Creator's server_export (ADR 0091).\n"
                + "# Start: docker compose up -d. EULA: set EULA=TRUE in .env once you agree to "
                + "https://aka.ms/MinecraftEULA.\nservices:\n");
        boolean behindProxy = ProxyForwarding.enabled(run) || ProxyForwarding.bungeeEnabled(run);
        Set<String> managed = new java.util.HashSet<>();
        Map<String, List<String>> sources = updatable(run, managed);
        List<String> jars = copy(platform, run, out.resolve("data"), world, managed, behindProxy, notes);
        String ports = "    ports:\n      - \"" + (platform.proxy() ? "25577:25577" : "25565:25565") + "\"\n"
                + (geyser(run) ? "      - \"19132:19132/udp\"\n" : "");
        if (platform == Platform.MINESTOM) {
            Files.writeString(out.resolve("Dockerfile"), "FROM eclipse-temurin:25-jre\nWORKDIR /data\n"
                    + "ENV SERVER_IP=0.0.0.0 SERVER_PORT=25565\n"
                    + "COPY data/ /data/\nEXPOSE 25565\nCMD [\"java\", \"-Xmx1G\", \"-jar\", \"server.jar\"]\n");
            Files.copy(run.resolve("server.jar"), out.resolve("data/server.jar"));
            compose.append("  server:\n    build: .\n    restart: unless-stopped\n    stdin_open: true\n").append(ports)
                    .append("    volumes:\n      - ./world:/data/world\n");
            notes.add("Minestom runs the project's own jar (built into the image); it has no RCON, so no backup "
                    + "service: back up ./world while the server is stopped");
        } else if (platform.proxy()) {
            compose.append("  proxy:\n    image: ").append(ITZG_PROXY).append("\n    restart: unless-stopped\n")
                    .append("    environment:\n      TYPE: ").append(platform.name()).append('\n')
                    .append(platform == Platform.VELOCITY ? "      VELOCITY_VERSION: \"" + pinned("velocityVersion")
                            + "\"\n" : "      WATERFALL_VERSION: \"" + pinned("waterfallVersion") + "\"\n")
                    .append(env(sources)).append(ports).append("    volumes:\n      - ./data:/server\n");
            listenEverywhere(out.resolve("data"), platform, run);
            List<Network.Member> members = Network.members(run);
            java.util.SequencedMap<String, String> addresses = new java.util.LinkedHashMap<>();
            for (Network.Member m : members) {
                addresses.put(m.name(), m.name() + ":25565");
                Path data = out.resolve("servers").resolve(m.name()).resolve("data");
                Set<String> none = new java.util.HashSet<>();
                List<String> backendJars = copy(Platform.PAPER, m.dir(), data, world, none, true, notes);
                compose.append("  ").append(m.name()).append(":\n    image: ").append(ITZG_SERVER)
                        .append("\n    restart: unless-stopped\n    environment:\n")
                        .append("      EULA: \"${EULA:-FALSE}\"\n      TYPE: PAPER\n      VERSION: \"")
                        .append(platform == Platform.WATERFALL ? pinned("waterfallMinecraftVersion")
                                : config.minecraft()).append("\"\n      MEMORY: \"2G\"\n")
                        .append("      ENABLE_RCON: \"true\"\n      RCON_PASSWORD: \"${RCON_PASSWORD}\"\n")
                        .append("    volumes:\n      - ./servers/").append(m.name()).append("/data:/data\n");
                compose.append(backup(m.name(), "./servers/" + m.name()));
                jars.addAll(backendJars.stream().map(j -> m.name() + "/" + j).toList());
            }
            if (!members.isEmpty()) {
                ProxyForwarding.servers(out.resolve("data"), platform, addresses);
                notes.add("the network's backends are services named after them; the proxy reaches them as "
                        + "<name>:25565 and only the proxy's port is published");
            }
        } else {
            String type = switch (platform) {
                case PAPER, PURPUR, FOLIA, FABRIC, QUILT, NEOFORGE, FORGE -> platform.name();
                default -> throw new IllegalStateException(platform.id());
            };
            compose.append("  mc:\n    image: ").append(ITZG_SERVER).append("\n    restart: unless-stopped\n")
                    .append("    environment:\n      EULA: \"${EULA:-FALSE}\"\n      TYPE: ").append(type)
                    .append("\n      VERSION: \"").append(minecraft(run)).append("\"\n");
            if (platform == Platform.NEOFORGE) {
                compose.append("      NEOFORGE_VERSION: \"").append(pinned("neoforgeVersion")).append("\"\n");
            } else if (platform == Platform.FORGE) {
                String forge = pinned("forgeVersion");
                compose.append("      FORGE_VERSION: \"").append(forge.substring(forge.indexOf('-') + 1))
                        .append("\"\n");
            }
            compose.append("      MEMORY: \"2G\"\n      ENABLE_RCON: \"true\"\n      RCON_PASSWORD: \"${RCON_PASSWORD}\"\n")
                    .append(env(sources)).append(ports).append("    volumes:\n      - ./data:/data\n");
            compose.append(backup("mc", "."));
        }
        Files.writeString(out.resolve("docker-compose.yml"), compose);
        Files.writeString(out.resolve(".env"), "# Set to TRUE once you agree to https://aka.ms/MinecraftEULA\n"
                + "EULA=FALSE\nRCON_PASSWORD=" + rcon + "\nBACKUP_INTERVAL=6h\n");
        Files.writeString(out.resolve("README.md"), dockerReadme(platform, sources, jars));
        return "exported " + platform.id() + " for Docker Compose into " + config.workspace().relativize(out)
                .toString().replace('\\', '/') + ": docker-compose.yml, .env, data/" + (platform.proxy()
                && !Network.members(run).isEmpty() ? ", servers/" : "") + ", README.md\n"
                + "server: " + (platform == Platform.MINESTOM ? "the project's jar in an eclipse-temurin:25 image"
                : (platform.proxy() ? ITZG_PROXY : ITZG_SERVER) + " (downloads and updates the server jar itself)")
                + "\nplugins copied: " + (jars.isEmpty() ? "none" : String.join(", ", jars))
                + updatesLine(sources)
                + (platform == Platform.MINESTOM || (platform.proxy() && Network.members(run).isEmpty()) ? ""
                : "\nbackups: " + ITZG_BACKUP + " every ${BACKUP_INTERVAL:-6h} into ./backups, kept 7 days");
    }

    /** The dev proxy listens on loopback; in a container that is unreachable, so it listens on all addresses. */
    private static void listenEverywhere(Path data, Platform proxy, Path run) throws IOException {
        Path file = data.resolve(proxy == Platform.WATERFALL ? ProxyForwarding.BUNGEE_CONFIG
                : ProxyForwarding.VELOCITY_TOML);
        if (Files.exists(file)) {
            int port = ProxyForwarding.listenPort(run, proxy);
            Files.writeString(file, Files.readString(file).replace("127.0.0.1:" + port, "0.0.0.0:" + port));
        }
    }

    private static String env(Map<String, List<String>> sources) {
        StringBuilder out = new StringBuilder();
        if (!sources.get("modrinth").isEmpty()) {
            out.append("      MODRINTH_PROJECTS: \"").append(String.join(",", sources.get("modrinth"))).append("\"\n");
        }
        if (!sources.get("spigot").isEmpty()) {
            out.append("      SPIGET_RESOURCES: \"").append(String.join(",", sources.get("spigot"))).append("\"\n");
        }
        return out.toString();
    }

    private static String updatesLine(Map<String, List<String>> sources) {
        List<String> kept = new ArrayList<>(sources.get("modrinth"));
        sources.get("spigot").forEach(s -> kept.add("spigot:" + s));
        return kept.isEmpty() ? "" : "\nkept up to date by the image: " + String.join(", ", kept);
    }

    /** An mc-backup service for {@code service}, whose data is at {@code base}/data. */
    private static String backup(String service, String base) {
        return "  " + service + "-backups:\n    image: " + ITZG_BACKUP + "\n    restart: unless-stopped\n"
                + "    depends_on:\n      - " + service + "\n    environment:\n"
                + "      BACKUP_INTERVAL: \"${BACKUP_INTERVAL:-6h}\"\n      INITIAL_DELAY: \"5m\"\n"
                + "      PRUNE_BACKUPS_DAYS: \"7\"\n      RCON_HOST: " + service + "\n"
                + "      RCON_PASSWORD: \"${RCON_PASSWORD}\"\n    volumes:\n      - " + base + "/data:/data:ro\n"
                + "      - " + base + "/backups:/backups\n";
    }

    private String dockerReadme(Platform platform, Map<String, List<String>> sources, List<String> jars) {
        return "# Production server\n\nMade by Minecraft Server Creator from the " + platform.displayName()
                + " dev server (ADR 0091).\n\n## Run\n\n1. Read https://aka.ms/MinecraftEULA; if you agree, set "
                + "`EULA=TRUE` in `.env`.\n2. `docker compose up -d`, then `docker compose logs -f`.\n3. Console: "
                + "`docker compose exec <service> rcon-cli`.\n\n## Updates\n\n- The server jar: the image downloads "
                + "the newest build of `VERSION` on each start; change `VERSION` (and the loader version) to move to "
                + "a new Minecraft version, then `docker compose up -d`.\n"
                + (updatesLine(sources).isEmpty() ? "" : "- Plugins from Modrinth and SpigotMC (`MODRINTH_PROJECTS`, "
                + "`SPIGET_RESOURCES`) update themselves on start.\n")
                + "- Your own plugins (" + (jars.isEmpty() ? "none" : String.join(", ", jars)) + "): build them, put "
                + "the new jar in `data/plugins/` (or `mods/`), restart.\n- The image itself: `docker compose pull && "
                + "docker compose up -d`.\n\n## Backups\n\n`*-backups` services save the world every "
                + "`BACKUP_INTERVAL` (6h) into `backups/` and delete ones older than 7 days. To restore, stop the "
                + "server, extract a backup over `data/`, start it.\n";
    }

    // ---- pterodactyl --------------------------------------------------------------------------------------------

    /** Platforms whose installer is a Java program: their egg installs in a JDK image instead of Alpine (ADR 0102). */
    private static final Set<Platform> JAVA_INSTALL = Set.of(Platform.NEOFORGE, Platform.FORGE, Platform.QUILT);

    /** The jar the egg starts: the one its install leaves, as the dev server's (ADR 0102), or server.jar. */
    private static String eggJar(Platform platform) {
        return JAVA_INSTALL.contains(platform) || platform == Platform.MINESTOM ? platform.serverJar() : "server.jar";
    }

    /** The loader version an egg installs: the one the dev server runs, pinned in this build of msc-core. */
    private static String loader(Platform platform) {
        return switch (platform) {
            case NEOFORGE -> pinned("neoforgeVersion");
            case FORGE -> pinned("forgeVersion");
            case QUILT -> pinned("quiltLoaderVersion");
            default -> null;
        };
    }

    /** The install script lines that put the server in /mnt/server for an egg. */
    private static String egg(Platform platform) {
        String fill = "BUILD=$(curl -fsSL https://fill.papermc.io/v3/projects/%s/versions/${VERSION}/builds/latest)\n"
                + "URL=$(echo \"$BUILD\" | jq -r '.downloads.\"server:default\".url')\n"
                + "curl -fsSL -o \"${SERVER_JARFILE}\" \"$URL\"\n";
        return switch (platform) {
            case PAPER, FOLIA, VELOCITY -> fill.formatted(platform.id());
            case WATERFALL -> fill.formatted("waterfall") + "mkdir -p modules\nfor m in $(echo \"$BUILD\" | jq -r "
                    + "'.downloads | to_entries[] | select(.key | startswith(\"module:\")) | .key[7:]'); do\n"
                    + "  curl -fsSL -o \"modules/$m.jar\" \"$(echo \"$BUILD\" | jq -r --arg k \"module:$m\" "
                    + "'.downloads[$k].url')\"\ndone\n";
            case PURPUR -> "curl -fsSL -o \"${SERVER_JARFILE}\" "
                    + "\"https://api.purpurmc.org/v2/purpur/${VERSION}/latest/download\"\n";
            case FABRIC -> "INSTALLER=$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer | jq -r "
                    + "'[.[] | select(.stable)][0].version')\nLOADER=$(curl -fsSL "
                    + "https://meta.fabricmc.net/v2/versions/loader/${VERSION} | jq -r '[.[] | "
                    + "select(.loader.stable)][0].loader.version')\ncurl -fsSL -o \"${SERVER_JARFILE}\" "
                    + "\"https://meta.fabricmc.net/v2/versions/loader/${VERSION}/${LOADER}/${INSTALLER}/server/jar\"\n";
            // NeoForge's --server-jar leaves server.jar, a starter that reads the installed libraries.
            case NEOFORGE -> "curl -fsSL -o installer.jar \"https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                    + "${LOADER_VERSION}/neoforge-${LOADER_VERSION}-installer.jar\"\njava -jar installer.jar "
                    + "--install-server . --server-jar\nrm -f installer.jar installer.jar.log\n";
            // Forge leaves forge-<version>-shim.jar, runnable on its own; the egg starts it under a fixed name.
            case FORGE -> "curl -fsSL -o installer.jar \"https://maven.minecraftforge.net/net/minecraftforge/forge/"
                    + "${LOADER_VERSION}/forge-${LOADER_VERSION}-installer.jar\"\njava -jar installer.jar "
                    + "--installServer .\ncp \"forge-${LOADER_VERSION}-shim.jar\" \"${SERVER_JARFILE}\"\n"
                    + "rm -f installer.jar installer.jar.log\n";
            // Quilt's launcher starts the vanilla jar the installer downloads beside it as server.jar.
            case QUILT -> "curl -fsSL -o installer.jar \"https://maven.quiltmc.org/repository/release/org/quiltmc/"
                    + "quilt-installer/" + pinned("quiltInstallerVersion") + "/quilt-installer-"
                    + pinned("quiltInstallerVersion") + ".jar\"\njava -jar installer.jar install server ${VERSION} "
                    + "${LOADER_VERSION} --download-server --install-dir=.\nrm -f installer.jar\n";
            // Minestom is the project's own jar, which comes in server-files.zip.
            case MINESTOM -> "";
        };
    }

    private String pterodactyl(Platform platform, Path run, Path out, boolean world, java.util.SequencedSet<String> notes)
            throws IOException {
        boolean behindProxy = ProxyForwarding.enabled(run) || ProxyForwarding.bungeeEnabled(run);
        Path staging = Files.createTempDirectory(Files.createDirectories(config.dataDir()), "export-");
        List<String> jars;
        try {
            jars = copy(platform, run, staging, world, Set.of(), behindProxy, notes);
            if (platform == Platform.MINESTOM) {
                Files.copy(run.resolve(platform.serverJar()), staging.resolve(platform.serverJar()));
                notes.add("Minestom runs the project's own jar (in server-files.zip); it binds SERVER_IP and SERVER_PORT, "
                        + "which Pterodactyl sets, when the project's main reads them (projects scaffolded since "
                        + "ADR 0102 do)");
            }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out.resolve("server-files.zip")))) {
                zipTree(staging, staging, zip);
            }
        } finally {
            Scaffold.deleteTree(staging);
        }
        String version = platform == Platform.VELOCITY ? pinned("velocityVersion")
                : platform == Platform.WATERFALL ? pinned("waterfallVersion") : minecraft(run);
        String done = platform == Platform.VELOCITY ? "Done (" : platform == Platform.WATERFALL ? "Listening on "
                : platform == Platform.MINESTOM ? "Minestom listening on " : ")! For help, type ";
        String startup = "java -Xms128M -XX:MaxRAMPercentage=95.0 -Dterminal.jline=false -Dterminal.ansi=true -jar "
                + "{{SERVER_JARFILE}}" + (platform.proxy() || platform == Platform.MINESTOM ? "" : " nogui");
        boolean jdk = JAVA_INSTALL.contains(platform);
        String script = (jdk ? "#!/bin/bash\n# Made by Minecraft Server Creator (ADR 0091, 0101)\nset -e\n"
                + "apt-get update -qq && apt-get install -y -qq curl jq > /dev/null\n"
                : "#!/bin/ash\n# Made by Minecraft Server Creator (ADR 0091)\napk add --no-cache curl jq\n")
                + "mkdir -p /mnt/server && cd /mnt/server\n" + egg(platform) + "echo \"installed " + platform.id()
                + " ${VERSION}" + (loader(platform) == null ? "" : " ${LOADER_VERSION}") + "\"\n";
        StringBuilder json = new StringBuilder("{\n  \"_comment\": \"Made by Minecraft Server Creator's server_export\",\n"
                + "  \"meta\": {\"version\": \"PTDL_v2\", \"update_url\": null},\n");
        json.append("  \"name\": ").append(quote(platform.displayName() + " (" + config.workspace().getFileName()
                + ")")).append(",\n  \"author\": \"msc@localhost\",\n  \"description\": ")
                .append(quote("The " + platform.displayName() + " server exported from MSC. Upload server-files.zip "
                        + "after the install and unpack it.")).append(",\n  \"features\": ")
                .append(platform.needsEula() ? "[\"eula\", \"java_version\", \"pid_limit\"]" : "[]")
                .append(",\n  \"docker_images\": {\"Java 25\": ").append(quote(YOLK)).append("},\n")
                .append("  \"file_denylist\": [],\n  \"startup\": ").append(quote(startup)).append(",\n")
                .append("  \"config\": {\n    \"files\": ").append(quote(platform.proxy() ? "{}"
                        : "{\"" + ServerPropertiesFile.NAME + "\": {\"parser\": \"properties\", \"find\": {\"server-ip\": \"0.0.0.0\", "
                        + "\"server-port\": \"{{server.build.default.port}}\"}}}"))
                .append(",\n    \"startup\": ").append(quote("{\"done\": " + quote(done) + "}"))
                .append(",\n    \"logs\": \"{}\",\n    \"stop\": ").append(quote(platform.stopCommand()))
                .append("\n  },\n  \"scripts\": {\"installation\": {\"script\": ").append(quote(script))
                .append(jdk ? ", \"container\": \"eclipse-temurin:25-jdk\", \"entrypoint\": \"bash\"}},\n"
                        : ", \"container\": \"ghcr.io/pterodactyl/installers:alpine\", \"entrypoint\": \"ash\"}},\n")
                .append("  \"variables\": [\n")
                .append(variable("Version", "VERSION", version, "The " + (platform.proxy() ? platform.displayName()
                        : "Minecraft") + " version to install; reinstall to update the server jar"))
                .append(loader(platform) == null ? "" : ",\n" + variable("Loader version", "LOADER_VERSION",
                        loader(platform), "The " + platform.displayName() + " version to install (the dev server's); "
                                + "reinstall after changing it"))
                .append(",\n").append(variable("Server jar", "SERVER_JARFILE", eggJar(platform), "The jar file to run"))
                .append("\n  ]\n}\n");
        Files.writeString(out.resolve("egg-" + platform.id() + ".json"), json);
        Files.writeString(out.resolve("README.md"), "# Pterodactyl\n\nMade by Minecraft Server Creator (ADR 0091).\n\n"
                + "1. Admin, Nests: import `egg-" + platform.id() + ".json`.\n2. Create a server with it (2 GB or "
                + "more); " + installs(platform, version) + ".\n3. Files: upload "
                + "`server-files.zip` and unarchive it (configs, plugins" + (world ? ", world" : "") + ").\n"
                + "4. Start it" + (platform.needsEula() ? "; the panel asks for the Minecraft EULA" : "") + ".\n\n"
                + (platform == Platform.MINESTOM ? "Updates: rebuild the project and upload its jar as `server.jar`."
                : "Updates: the panel's Reinstall downloads " + (loader(platform) == null ? "the newest build of "
                + "`VERSION`" : "`LOADER_VERSION` for `VERSION`") + " (change it for a new version); replace jars in `"
                + platform.addonDir() + "/`.") + "\nBackups: Schedules, a task \"Create backup\" "
                + "(for example every 6 hours), and Backups to restore.\n");
        return "exported " + platform.id() + " for Pterodactyl into " + config.workspace().relativize(out).toString()
                .replace('\\', '/') + ": egg-" + platform.id() + ".json (" + installs(platform, version)
                + "), server-files.zip, README.md\nplugins in the zip: "
                + (jars.isEmpty() ? "none" : String.join(", ", jars))
                + "\nbackups and updates: the panel's schedules and reinstall (README.md)";
    }

    /** What the egg's install does, for the README and the result. */
    private static String installs(Platform platform, String version) {
        return platform == Platform.MINESTOM ? "the server is the project's jar in server-files.zip"
                : "the install downloads " + platform.displayName() + " " + version
                + (loader(platform) == null ? "" : " with loader " + loader(platform));
    }

    private static String variable(String name, String env, String value, String description) {
        return "    {\"name\": " + quote(name) + ", \"description\": " + quote(description) + ", \"env_variable\": "
                + quote(env) + ", \"default_value\": " + quote(value) + ", \"user_viewable\": true, "
                + "\"user_editable\": true, \"rules\": \"required|string|max:64\", \"field_type\": \"text\"}";
    }

    private static String quote(String s) {
        return new com.google.gson.Gson().toJson(s);
    }

    private static void zipTree(Path root, Path dir, ZipOutputStream zip) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            for (Path c : children.sorted().toList()) {
                if (Files.isDirectory(c)) {
                    zipTree(root, c, zip);
                } else {
                    zip.putNextEntry(new ZipEntry(root.relativize(c).toString().replace('\\', '/')));
                    Files.copy(c, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static String pinned(String key) {
        String v = Templates.versions().get(key);
        if (v == null) {
            throw new ToolException("this build of msc-core has no " + key + " pinned");
        }
        return v;
    }
}
