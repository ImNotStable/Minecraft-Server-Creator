package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.api.ApiIndex;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform API lookups from a local index, built on first use and rebuilt when the platform or its jars change
 * (another project_scaffold or server_setup platform, or a newer API version Gradle downloaded).
 */
public final class ApiTools {
    private final CoreConfig config;
    private ApiIndex index;
    /** The jars and their modification times the index was built from. */
    private List<JarStamp> builtFrom;

    private record JarStamp(Path jar, long modified) {
    }

    public ApiTools(CoreConfig config) {
        this.config = config;
    }

    synchronized ApiIndex index() throws IOException {
        List<Path> jars = config.apiJars();
        if (jars.isEmpty()) {
            String platform = Platform.current(config).agent();
            // The Gradle user home the core's builds use, also when GRADLE_USER_HOME moves it (#666).
            jars = ApiIndex.findJars(config.gradleUserHome(), platform);
            if (jars.isEmpty()) {
                throw new ToolException("no " + platform + " API jar found in the Gradle caches of "
                        + config.gradleUserHome() + ". Build the project once (dev_cycle) so "
                        + "Gradle downloads it, or set MSC_API_JARS.");
            }
        }
        List<JarStamp> stamps = new ArrayList<>();
        for (Path jar : jars) {
            stamps.add(new JarStamp(jar, Files.exists(jar) ? Files.getLastModifiedTime(jar).toMillis() : -1));
        }
        if (index == null || !stamps.equals(builtFrom)) {
            index = ApiIndex.load(jars);
            builtFrom = stamps;
        }
        return index;
    }

    public synchronized String status() {
        if (index == null) {
            return "not loaded yet (loads on first api_search)";
        }
        // Fabric indexes ~50 module jars; name the first few so msc_status stays short.
        List<String> jars = index.jars().stream().map(p -> p.getFileName().toString()).toList();
        return index.size() + " classes from " + (jars.size() <= 3 ? jars
                : jars.subList(0, 3) + " and " + (jars.size() - 3) + " more jars");
    }

    /** Most matches api_search lists. */
    static final int MAX_SEARCH = 100;
    /** Most members api_class lists. */
    static final int MAX_MEMBERS = 300;

    public void register(ToolRegistry registry) {
        registry.add("api_search",
                "Search the workspace platform's API by class, method, field or constant name; "
                        + "one line per match with full signature.",
                Schema.of().req("query", "string", "Words that must all match, for example \"player teleport\"")
                        .opt("kind", "string", "class, interface, enum, record, annotation or event")
                        .range("limit", 1, MAX_SEARCH, "Default 15")
                        .maxChars(),
                args -> {
                    String kind = args.str("kind", null);
                    if (kind != null && !List.of("class", "interface", "enum", "record", "annotation", "event").contains(kind)) {
                        throw new ToolException("kind must be class, interface, enum, record, annotation or event");
                    }
                    int limit = args.integer("limit", 15);
                    return Budget.truncate(index().search(args.str("query"), kind, limit, MAX_SEARCH), Budget.of(args),
                            "Add words or lower limit.");
                });
        registry.add("api_class",
                "Show one API class: supertypes, constants (with inherited) and public members with signatures.",
                Schema.of().req("name", "string", "Simple or full class name, such as Player")
                        .opt("filter", "string", "Only members whose name contains this")
                        .opt("inherited", "boolean", "Include members of supertypes")
                        .range("limit", 1, MAX_MEMBERS, "Default 40")
                        .maxChars(),
                args -> Budget.truncate(index().describe(args.str("name"), args.str("filter", null),
                        args.bool("inherited", false), args.integer("limit", 40), MAX_MEMBERS),
                        Budget.of(args), "Use filter."));
    }
}
