package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #419, #434: the line counts tools.md gives in its "Results" lists are the real ones. Each tool here runs in every
 * shape its result can take: every result must be inside the documented range, and the shortest and the longest must
 * be its ends, so a doc that promises a count the tool does not keep, or a range wider than it is, fails. A tool whose
 * entry gains a count must get a test here ({@link #COUNTED}). server_setup has too many shapes for a useful count;
 * its entry names its lines instead, and each named line must turn up and no other.
 */
class DocumentedResultLinesTest {
    private static final String DOC = "msc-docs/docs/tools.md";
    /** The tools whose tools.md entry starts with a line count, each with a test below. */
    private static final Set<String> COUNTED = Set.of("msc_status", "msc_workspace", "project_scaffold",
            "server_detach", "server_stop", "server_wait");

    @TempDir
    Path root;

    private static String doc() throws IOException {
        return Files.readString(RepoFiles.path("toolDoc", DOC));
    }

    /** The documented range of {@code tool}'s result: {@code N lines}, {@code N to M lines} or {@code N or M lines}. */
    private static int[] documented(String tool) throws IOException {
        Matcher m = Pattern.compile("(?m)^- `" + tool + "`: (\\d+)(?: (?:to|or) (\\d+))? lines?\\b").matcher(doc());
        assertThat(m.find()).as("tools.md gives the line count of " + tool).isTrue();
        int min = Integer.parseInt(m.group(1));
        return new int[] {min, m.group(2) == null ? min : Integer.parseInt(m.group(2))};
    }

    private static void matches(String tool, List<String> results) throws IOException {
        assertThat(COUNTED).as("a counted tool is listed in COUNTED").contains(tool);
        int[] range = documented(tool);
        TreeSet<Integer> seen = new TreeSet<>();
        for (String result : results) {
            int lines = (int) result.lines().count();
            assertThat(lines).as("%s result:%n%s", tool, result).isBetween(range[0], range[1]);
            seen.add(lines);
        }
        assertThat(List.of(seen.first(), seen.last())).as(tool + ": the documented range is the real one")
                .containsExactly(range[0], range[1]);
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    /**
     * #458: tools.md's cap rule is the one every tool's result obeys. The registry applies {@link Budget#cap} to every
     * result (ToolRegistryTest), so here each registered tool's largest possible result, asked with the largest
     * maxChars, is the documented cap: 24,000 for a tool whose row lists {@code maxChars}, 6,000 for every other.
     */
    @Test
    void everyToolsLargestResultIsTheDocumentedCap() throws Exception {
        String doc = doc();
        assertThat(doc).contains("The default cap is " + String.format("%,d", Budget.DEFAULT_CHARS) + " characters")
                .contains("accept 200 to " + String.format("%,d", Budget.MAX_CHARS));
        com.google.gson.JsonObject huge = new com.google.gson.JsonObject();
        huge.addProperty("maxChars", 1_000_000);
        try (CoreHarness h = new CoreHarness(Files.createDirectories(root.resolve("caps")))) {
            for (Tool tool : h.core.registry().tools()) {
                Matcher row = Pattern.compile("(?m)^\\| `" + tool.name() + "` \\|.*$").matcher(doc);
                assertThat(row.find()).as("tools.md has a row for " + tool.name()).isTrue();
                boolean documented = row.group().contains("`maxChars`? (integer, " + Budget.MIN_CHARS + " to "
                        + Budget.MAX_CHARS + ")");
                assertThat(Budget.takesMaxChars(tool)).as(tool.name() + " takes maxChars as tools.md says")
                        .isEqualTo(documented);
                assertThat(Budget.cap(tool, new Args(huge))).as(tool.name() + "'s largest result")
                        .isEqualTo(documented ? Budget.MAX_CHARS : Budget.DEFAULT_CHARS);
            }
        }
    }

    @Test
    void everyEntryThatGivesALineCountHasATestHere() throws IOException {
        // Also "about N lines" or "N line, or M ...": any count the entry opens with.
        Matcher m = Pattern.compile("(?m)^- `(\\w+)`: (?:about |up to |at most )?\\d+(?: (?:to|or) \\d+)? lines?\\b")
                .matcher(doc());
        Set<String> counted = new TreeSet<>();
        while (m.find()) {
            counted.add(m.group(1));
        }
        assertThat(counted).as("tools.md entries that give a line count").containsExactlyInAnyOrderElementsOf(COUNTED);
    }

    @Test
    void mscStatus() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        String plain;
        try (CoreHarness h = new CoreHarness(ws)) {
            h.ok("msc_status");
            plain = h.ok("msc_status");
        }
        // Both setup warnings: the workspace is the MSC repo, and the install is older than that checkout.
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.writeString(repo.resolve("settings.gradle.kts"), "rootProject.name = \"minecraft-server-creator\"\n");
        Path source = Files.createDirectories(repo.resolve("msc-core/src/main/java")).resolve("Main.java");
        Files.writeString(source, "class Main {}");
        Files.setLastModifiedTime(source, FileTime.fromMillis(2_000_000_000_000L));
        Path install = Files.createDirectories(root.resolve("install"));
        Files.writeString(install.resolve("install.properties"),
                "source=" + repo.toString().replace("\\", "\\\\") + "\ninstalled=1000\n");
        String warned;
        try (CoreHarness h = new CoreHarness(repo, c -> c.withInstallDir(install))) {
            h.ok("msc_status");
            warned = h.ok("msc_status");
        }
        assertThat(warned).contains("\nwarning: the workspace is the Minecraft Server Creator repo")
                .contains("\nwarning: this msc-core (installed ");
        matches("msc_status", List.of(plain, warned));
    }

    @Test
    void mscWorkspace() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        Files.createDirectories(root.resolve("other"));
        Files.createDirectories(root.resolve("third"));
        try (CoreHarness h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withMachineEula(MachineEula.forHome(root.resolve("home"))))) {
            String current = h.ok("msc_workspace");
            String same = h.ok("msc_workspace", "path", ws.toString());
            String quiet = h.ok("msc_workspace", "path", root.resolve("other").toString());
            h.ok("server_start", "timeoutSeconds", 60);
            String stopped = h.ok("msc_workspace", "path", root.resolve("third").toString());
            matches("msc_workspace", List.of(current, same, quiet, stopped));
        }
    }

    @Test
    void projectScaffold() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        try (CoreHarness h = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class))
                .withMachineEula(MachineEula.forHome(root.resolve("home"))))) {
            matches("project_scaffold", List.of(
                    h.ok("project_scaffold", "name", "Lobby", "dir", "lobby", "template", "minestom-server"),
                    h.ok("project_scaffold", "name", "Arena", "dir", "arena"),
                    h.ok("project_scaffold", "name", "Gems", "dir", "gems", "template", "neoforge-mod")));
        }
    }

    @Test
    void serverStopWaitAndDetach() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        // error.flag: the fake server prints a startup error, so the log has more than 5 lines since the start.
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/error.flag"), "");
        // A server folder has one; server_detach offers the pack there.
        Files.writeString(ws.resolve("run/server.properties"), "motd=test\n");
        try (CoreHarness h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withMachineEula(MachineEula.forHome(root.resolve("home"))))) {
            List<String> stops = new ArrayList<>(List.of(h.ok("server_stop")));
            h.ok("server_start", "timeoutSeconds", 60);
            String matched = h.ok("server_wait", "until", "Starting minecraft server", "timeoutSeconds", 60);
            String lastLines = h.ok("server_wait", "until", "^never$", "timeoutSeconds", 1);
            String noLines = h.ok("server_wait", "until", "^never$", "timeoutSeconds", 1);
            assertThat(lastLines).startsWith("no line matched in 1s; last lines:\n");
            matches("server_wait", List.of(matched, lastLines, noLines));

            String noPack = h.ok("server_detach");
            stops.add(h.ok("server_stop"));
            h.ok("server_start", "timeoutSeconds", 60);
            h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
            h.ok("pack", "apply", false);
            String hosted = h.ok("server_detach");
            assertThat(hosted).contains("\nserver.properties now offers the pack too");
            matches("server_detach", List.of(noPack, hosted));
            stops.add(h.ok("server_stop"));
            assertThat(stops).containsExactly("server was not running", "server stopped",
                    "server stopped, and the pack host it kept running");
            matches("server_stop", stops);
        }
    }

    /** A line server_setup can give: the text tools.md quotes for it and a pattern for the real line. */
    private record SetupLine(String documented, String line) {
    }

    private static final List<SetupLine> SETUP_LINES = List.of(
            new SetupLine("`wrote N default config files`", "wrote \\d+ default config files"),
            new SetupLine("`mode game: survival, normal difficulty from the next start`",
                    "mode game: survival, normal difficulty from the next start"),
            new SetupLine("`mode build: creative, peaceful difficulty from the next start`",
                    "mode build: creative, peaceful difficulty from the next start"),
            new SetupLine("`paper <version> build <n> (downloaded)` or `(cached)`",
                    "paper \\S+ build \\d+ \\((downloaded|cached)\\)"),
            new SetupLine("`purpur <version> build <n> (installed)` or `(already there)`",
                    "purpur \\S+ build \\d+ \\((installed|already there)\\)"),
            new SetupLine("NeoForge and Forge `<name> <version> (installed)` or `(already there)`",
                    "(neoforge|forge) \\S+ \\((installed|already there)\\)"),
            new SetupLine("`fabric launcher for ...`", "fabric launcher for .*"),
            new SetupLine("`quilt loader <version> (installed)`", "quilt loader \\S+ \\((installed|already there)\\)"),
            new SetupLine("`fabric api <version>`", "fabric api \\S+ \\((downloaded|cached)\\)"),
            new SetupLine("`agent installed in mods/` (or `plugins/`)", "agent installed in (mods|plugins)/"),
            new SetupLine("`warning: msc <agent> agent not found ...`", "warning: msc \\w+ agent not found\\b.*"),
            new SetupLine("`geyser <version> build <n> (downloaded), floodgate ...`",
                    "geyser \\S+ build \\d+ \\((downloaded|cached|already there)\\), floodgate .*"),
            new SetupLine("`bedrock.port was taken, moved to N`", "bedrock\\.port was taken, moved to \\d+"),
            new SetupLine("`bedrock: UDP <address>:<port> (Geyser, fronting Java port <port>; ...)`",
                    "bedrock: UDP \\S+:\\d+ \\(Geyser, fronting Java port \\d+; .*\\)"),
            new SetupLine("`removed Geyser and Floodgate from plugins/ (their config folders stay)`",
                    "removed Geyser and Floodgate from plugins/ \\(their config folders stay\\)"),
            new SetupLine("`eula.txt has eula=true from the owner's one-time acceptance for this machine (...)`",
                    "eula\\.txt has eula=true from the owner's one-time acceptance for this machine \\(.*\\)"),
            new SetupLine("`note: eula.txt has eula=true; ...`", "note: eula\\.txt has eula=true; .*"),
            new SetupLine("`the Minecraft EULA is not accepted, so server_start refuses. Next: ask the user ...`",
                    "the Minecraft EULA is not accepted, so server_start refuses\\. Next: ask the user .*"),
            new SetupLine("`On yes: server_setup acceptEula=true ...`", "On yes: server_setup acceptEula=true .*"),
            new SetupLine("`Minecraft EULA (...) accepted for this machine ...`",
                    "Minecraft EULA \\(.*\\) accepted for this machine.*"),
            new SetupLine("`<platform> server ready in <folder>; next: server_start or dev_cycle`",
                    "\\w+ server ready in .*; next: server_start or dev_cycle"),
            new SetupLine("`minestom needs no server download ...`", "minestom needs no server download .*"),
            new SetupLine("`mode ignored: ...`", "mode ignored: .*"));

    @Test
    void serverSetupNamesItsLinesInsteadOfACount() throws Exception {
        Matcher m = Pattern.compile("(?m)^- `server_setup`: (.*)$").matcher(doc());
        assertThat(m.find()).as("tools.md has a server_setup entry").isTrue();
        String entry = m.group(1);
        List<String> results = new ArrayList<>();
        MachineEula machineEula = MachineEula.forHome(root.resolve("home"));
        try (FakeDownloads downloads = FakeDownloads.start()) {
            Path paper = Files.createDirectories(root.resolve("paper"));
            try (CoreHarness h = new CoreHarness(paper, c -> downloads.apply(c).withMachineEula(machineEula))) {
                results.add(h.ok("server_setup", "platform", "paper"));
                agents(paper);
                // The user's yes, in this test's own temp home only: later setups get the machine acceptance line.
                results.add(h.ok("server_setup", "mode", "game", "acceptEula", true));
            }
            Path purpur = agents(Files.createDirectories(root.resolve("purpur")));
            Files.createDirectories(purpur.resolve("run"));
            Files.writeString(purpur.resolve("run/eula.txt"), "eula=true\n");
            try (CoreHarness h = new CoreHarness(purpur, c -> downloads.apply(c)
                    .withMachineEula(MachineEula.forHome(root.resolve("nobody"))))) {
                results.add(h.ok("server_setup", "platform", "purpur", "mode", "build"));
                results.add(h.ok("server_setup", "bedrock", true));
                // Another program holds the configured Bedrock port: the next setup moves it.
                Path geyser = purpur.resolve("run/plugins/Geyser-Spigot/config.yml");
                try (java.net.DatagramSocket held = new java.net.DatagramSocket(0,
                        java.net.InetAddress.getByName("127.0.0.1"))) {
                    Files.writeString(geyser, Files.readString(geyser)
                            .replaceAll("(?m)^  port: \\d+$", "  port: " + held.getLocalPort()));
                    results.add(h.ok("server_setup"));
                }
                results.add(h.ok("server_setup", "bedrock", false));
            }
            for (String platform : List.of("fabric", "quilt", "neoforge", "forge")) {
                Path ws = agents(Files.createDirectories(root.resolve(platform)));
                try (CoreHarness h = new CoreHarness(ws, c -> downloads.apply(c).withMachineEula(machineEula))) {
                    results.add(h.ok("server_setup", "platform", platform));
                }
            }
            try (CoreHarness h = new CoreHarness(Files.createDirectories(root.resolve("minestom")))) {
                results.add(h.ok("server_setup", "platform", "minestom", "mode", "build"));
            }
        }
        List<String> lines = results.stream().flatMap(String::lines).toList();
        for (String line : lines) {
            assertThat(SETUP_LINES).as("tools.md describes this server_setup line: " + line)
                    .anyMatch(l -> line.matches(l.line()));
        }
        for (SetupLine l : SETUP_LINES) {
            assertThat(entry).as("tools.md names this server_setup line").contains(l.documented());
            assertThat(lines).as("a server_setup line like " + l.documented()).anyMatch(line -> line.matches(l.line()));
        }
    }

    /** Puts an agent jar for each agent platform where server_setup looks for it. */
    private static Path agents(Path ws) throws IOException {
        Path dir = Files.createDirectories(ws.resolve(".msc/agent"));
        for (String agent : List.of("paper", "fabric", "neoforge", "forge")) {
            Files.writeString(dir.resolve("msc-agent-" + agent + "-0.1.0.jar"), "agent " + agent);
        }
        return ws;
    }
}
