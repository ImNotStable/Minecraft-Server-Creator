package io.github.imnotstable.msc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps {@code msc-docs/docs/tools.md} in step with the tool definitions in code (ADR 0026). The doc names its
 * tables with markers, {@code <!-- tools: a b -->} ... {@code <!-- /tools -->}; everything between them is generated
 * from each tool's description and argument schema, everything outside stays hand-written. Every tool must sit in
 * exactly one table. The wiki's tool page stays hand-worded but must name exactly the same tools.
 *
 * <p>{@code ./gradlew :msc-core:toolDocs} rewrites the doc; {@code checkToolDocs} (part of {@code check}) fails when
 * it, or the wiki page, is out of date.
 */
public final class ToolDocs {
    static final Pattern TABLE = Pattern.compile("<!-- tools: ([a-z_ ]+) -->\\n.*?<!-- /tools -->", Pattern.DOTALL);
    static final Pattern SUMMARY = Pattern.compile("<!-- tool-list -->.*?<!-- /tool-list -->", Pattern.DOTALL);
    static final Pattern WIKI_ROW = Pattern.compile("(?m)^\\| `([a-z_]+)` \\|");
    static final Pattern WIKI_COUNT = Pattern.compile("All (\\d+) tools");

    private ToolDocs() {
    }

    /** The doc with every marked table and the tool-list summary regenerated. Throws when tools and markers disagree. */
    public static String render(String doc, Collection<Tool> tools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        tools.forEach(t -> byName.put(t.name(), t));
        List<String> problems = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        StringBuilder out = new StringBuilder();
        Matcher m = TABLE.matcher(doc);
        int last = 0;
        while (m.find()) {
            List<Tool> rows = new ArrayList<>();
            for (String name : m.group(1).strip().split(" +")) {
                Tool tool = byName.get(name);
                if (tool == null) {
                    problems.add("the doc lists " + name + ", which is not a tool");
                } else if (!placed.add(name)) {
                    problems.add(name + " is in two tables");
                } else {
                    rows.add(tool);
                }
            }
            out.append(doc, last, m.start()).append("<!-- tools: ").append(m.group(1).strip()).append(" -->\n")
                    .append(table(rows)).append("<!-- /tools -->");
            last = m.end();
        }
        out.append(doc, last, doc.length());
        for (String name : byName.keySet()) {
            if (!placed.contains(name)) {
                problems.add(name + " is in no table: add it to a <!-- tools: ... --> marker");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
        return SUMMARY.matcher(out).replaceAll(Matcher.quoteReplacement(
                "<!-- tool-list -->" + summary(tools) + "<!-- /tool-list -->"));
    }

    /** One line on what the tool list costs; the same JSON shape the MCP client receives, give or take framing. */
    static String summary(Collection<Tool> tools) {
        int chars = listJson(tools).length();
        return "The tool definitions (" + tools.size() + " tools: names, descriptions, argument schemas) are "
                + String.format("%,d", chars) + " characters, about " + String.format("%,d", (chars + 3) / 4)
                + " tokens in every conversation before MCP framing.";
    }

    static String listJson(Collection<Tool> tools) {
        JsonArray array = new JsonArray();
        for (Tool t : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.inputSchema());
            array.add(o);
        }
        return array.toString();
    }

    static String table(List<Tool> tools) {
        StringBuilder out = new StringBuilder("| Tool | Arguments | What it does |\n| --- | --- | --- |\n");
        for (Tool t : tools) {
            out.append("| `").append(t.name()).append("` | ").append(arguments(t.inputSchema())).append(" | ")
                    .append(cell(t.description())).append(" |\n");
        }
        return out.toString();
    }

    /** {@code `name` (type): description}, required ones first as declared, optional ones marked with {@code ?}. */
    static String arguments(JsonObject schema) {
        JsonObject props = schema.getAsJsonObject("properties");
        if (props == null || props.isEmpty()) {
            return "none";
        }
        Set<String> required = new LinkedHashSet<>();
        JsonArray req = schema.getAsJsonArray("required");
        if (req != null) {
            req.forEach(e -> required.add(e.getAsString()));
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : props.entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            String type = p.get("type").getAsString();
            if (type.equals("array")) {
                type = "list of " + p.getAsJsonObject("items").get("type").getAsString();
            }
            String desc = p.has("description") ? p.get("description").getAsString() : "";
            parts.add("`" + e.getKey() + "`" + (required.contains(e.getKey()) ? "" : "?") + " (" + type + ")"
                    + (desc.isEmpty() ? "" : ": " + cell(desc)));
        }
        return String.join("<br>", parts);
    }

    private static String cell(String text) {
        // Markdown would read <name> as an HTML tag and | as a column break.
        return text.replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|").replace("\r", "").strip().replace("\n", "<br>");
    }

    /** Problems with the wiki's tool page: tools it misses or names that are not tools, and a wrong count. */
    public static List<String> checkWiki(String page, Collection<Tool> tools) {
        Set<String> names = new LinkedHashSet<>();
        tools.forEach(t -> names.add(t.name()));
        Set<String> listed = new LinkedHashSet<>();
        Matcher m = WIKI_ROW.matcher(page);
        while (m.find()) {
            listed.add(m.group(1));
        }
        List<String> problems = new ArrayList<>();
        names.stream().filter(n -> !listed.contains(n)).forEach(n -> problems.add("the wiki tool page misses " + n));
        listed.stream().filter(n -> !names.contains(n)).forEach(n -> problems.add("the wiki tool page lists " + n
                + ", which is not a tool"));
        Matcher count = WIKI_COUNT.matcher(page);
        if (count.find() && Integer.parseInt(count.group(1)) != names.size()) {
            problems.add("the wiki tool page says \"All " + count.group(1) + " tools\"; there are " + names.size());
        }
        return problems;
    }

    /** Problems with the hand-written Platforms table in tools.md: each {@link Platform} needs a row. */
    public static List<String> checkPlatforms(String doc) {
        int start = doc.indexOf("### Platforms");
        String section = start < 0 ? "" : doc.substring(start, nextHeading(doc, start));
        List<String> problems = new ArrayList<>();
        for (Platform p : Platform.values()) {
            if (!section.contains("| `" + p.id() + "` |")) {
                problems.add("tools.md's Platforms table has no row for " + p.id());
            }
        }
        return problems;
    }

    private static int nextHeading(String doc, int from) {
        int next = doc.indexOf("\n#", from + 1);
        return next < 0 ? doc.length() : next;
    }

    /** {@code write|check <tools.md> <wiki Tools-reference.md>}. Check exits 1 with what is wrong. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("write", "check").contains(args[0])) {
            System.err.println("usage: ToolDocs write|check <tools.md> <Tools-reference.md>");
            System.exit(2);
        }
        Path docFile = Path.of(args[1]);
        Path wikiFile = Path.of(args[2]);
        List<String> problems = new ArrayList<>();
        Path workspace = Files.createTempDirectory("msc-tool-docs");
        try (Core core = new Core(CoreConfig.forWorkspace(workspace))) {
            Collection<Tool> tools = core.registry().tools();
            String doc = Files.readString(docFile, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String fresh;
            try {
                fresh = render(doc, tools);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
                fresh = doc;
            }
            if (args[0].equals("write")) {
                if (!fresh.equals(doc)) {
                    Files.writeString(docFile, fresh, StandardCharsets.UTF_8);
                    System.out.println("updated " + docFile);
                }
            } else if (!fresh.equals(doc)) {
                problems.add(docFile + " is out of date with the tool definitions");
            }
            problems.addAll(checkPlatforms(fresh));
            problems.addAll(checkWiki(Files.readString(wikiFile, StandardCharsets.UTF_8), tools));
        } finally {
            deleteTree(workspace);
        }
        if (!problems.isEmpty()) {
            System.err.println(String.join("\n", problems));
            System.err.println("Run ./gradlew :msc-core:toolDocs, then fix by hand what it cannot (markers, wiki page).");
            System.exit(1);
        }
        // The core's services may leave non-daemon threads behind; this is a one-shot tool.
        System.exit(0);
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
