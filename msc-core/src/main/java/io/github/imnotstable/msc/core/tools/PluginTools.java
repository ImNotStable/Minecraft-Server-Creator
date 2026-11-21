package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.AddonCheck;
import io.github.imnotstable.msc.core.server.AddonSources;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code plugins}: search Modrinth, Hangar and SpigotMC, install by id with required dependencies, and check the
 * plugins or mods folder (ADR 0057). Search results come in each source's order; nothing is suggested.
 */
final class PluginTools {
    /** In the server folder: the Minecraft version server_setup installed, which installs match. */
    static final String VERSION_FILE = ".msc-minecraft";

    private final CoreConfig config;
    private final ServerManager server;
    private final AddonSources sources;

    PluginTools(CoreConfig config, ServerManager server, AddonSources sources) {
        this.config = config;
        this.server = server;
        this.sources = sources;
    }

    String run(Args args) throws IOException, InterruptedException {
        Platform platform = server.platform();
        String search = args.str("search", null);
        List<String> install = args.has("install") ? args.strings("install") : List.of();
        if (search != null && !install.isEmpty()) {
            throw new ToolException("give search or install, not both");
        }
        if (platform.addonDir() == null) {
            throw new ToolException(platform.displayName() + " has no plugins folder: add a library to the project's "
                    + "build.gradle.kts instead");
        }
        if (search != null) {
            if (search.isBlank()) {
                throw new ToolException("search needs words, such as search=permissions");
            }
            List<String> hits = sources.search(search.strip(), platform);
            return hits.isEmpty() ? "no " + platform.displayName() + " matches for \"" + search.strip() + "\""
                    : String.join("\n", hits) + "\ninstall with plugins install=<id>";
        }
        Path dir = config.serverDir().resolve(platform.addonDir());
        StringBuilder out = new StringBuilder();
        if (!install.isEmpty()) {
            if (server.running()) {
                throw new ToolException("stop the server before installing: a running server does not load new jars");
            }
            List<AddonSources.Ref> refs = new ArrayList<>();
            for (String s : install) {
                refs.add(AddonSources.Ref.of(s));
            }
            Path versionFile = config.serverDir().resolve(VERSION_FILE);
            String minecraft = Files.exists(versionFile) ? Files.readString(versionFile).strip() : config.minecraft();
            for (AddonSources.Installed i : sources.install(refs, platform, minecraft, dir)) {
                replaceOlder(dir, i.jar());
                out.append("installed ").append(i.ref()).append(i.version() == null ? "" : " " + i.version())
                        .append(" -> ").append(platform.addonDir()).append('/').append(i.jar().getFileName());
                if (i.note() != null) {
                    out.append(" (").append(i.note()).append(')');
                }
                out.append('\n');
                i.missing().forEach(m -> out.append("not installed: ").append(m).append('\n'));
            }
        } else {
            for (AddonCheck.Addon a : AddonCheck.read(dir)) {
                out.append(a.describe(dir)).append('\n');
            }
            if (out.isEmpty()) {
                out.append("no jars in ").append(platform.addonDir()).append("/\n");
            }
        }
        List<String> problems = AddonCheck.problems(config.serverDir(), platform, true);
        if (problems.isEmpty()) {
            out.append("check: ok");
        } else {
            out.append("problems:\n");
            problems.forEach(p -> out.append("- ").append(p).append('\n'));
        }
        return out.toString().strip();
    }

    /** Deletes the other jars in {@code dir} that declare the same id as {@code jar}: an install replaces, never adds a copy. */
    static void replaceOlder(Path dir, Path jar) throws IOException {
        String id = AddonCheck.read(dir).stream().filter(a -> a.jar().equals(jar)).map(AddonCheck.Addon::id)
                .findFirst().orElse(null);
        if (id == null) {
            return;
        }
        for (AddonCheck.Addon a : AddonCheck.read(dir)) {
            if (!a.jar().equals(jar) && id.equalsIgnoreCase(a.id())) {
                Files.delete(a.jar());
            }
        }
    }
}
