package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.api.ApiIndex;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Platform API lookups from a local index, built on first use. */
public final class ApiTools {
    private final CoreConfig config;
    private ApiIndex index;

    public ApiTools(CoreConfig config) {
        this.config = config;
    }

    private synchronized ApiIndex index() throws IOException {
        if (index == null) {
            List<Path> jars = config.apiJars();
            if (jars.isEmpty()) {
                String platform = Platform.current(config).id();
                jars = ApiIndex.findJars(Path.of(System.getProperty("user.home"), ".gradle"), platform);
                if (jars.isEmpty()) {
                    throw new ToolException("no " + platform + " API jar found. Build the project once (dev_cycle) so "
                            + "Gradle downloads it, or set MSC_API_JARS.");
                }
            }
            index = ApiIndex.load(jars);
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

    public void register(ToolRegistry registry) {
        registry.add("api_search",
                "Search the platform API (Paper, Fabric or Minestom) by class, method, field or enum constant name. "
                        + "Returns one line per match with full signatures.",
                Schema.of().req("query", "string", "Words that must all match, for example \"player teleport\"")
                        .opt("kind", "string", "Only classes of this kind: class, interface, enum, record, annotation or event")
                        .opt("limit", "integer", "Default 15"),
                args -> {
                    String kind = args.str("kind", null);
                    if (kind != null && !List.of("class", "interface", "enum", "record", "annotation", "event").contains(kind)) {
                        throw new ToolException("kind must be class, interface, enum, record, annotation or event");
                    }
                    int limit = Math.max(1, Math.min(100, args.integer("limit", 15)));
                    return Budget.truncate(index().search(args.str("query"), kind, limit), Budget.MAX_CHARS, "Lower limit.");
                });
        registry.add("api_class",
                "Show one API class: supertypes, enum constants and public members with signatures.",
                Schema.of().req("name", "string", "Simple or full class name, for example Player or org.bukkit.entity.Player")
                        .opt("filter", "string", "Only members whose name contains this")
                        .opt("inherited", "boolean", "Include members of supertypes. Default false")
                        .opt("limit", "integer", "Default 40"),
                args -> Budget.truncate(index().describe(args.str("name"), args.str("filter", null),
                        args.bool("inherited", false), Math.max(1, Math.min(300, args.integer("limit", 40)))),
                        Budget.MAX_CHARS, "Use filter."));
    }
}
