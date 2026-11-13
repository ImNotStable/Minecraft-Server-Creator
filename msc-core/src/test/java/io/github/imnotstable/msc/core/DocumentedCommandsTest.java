package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.core.server.EulaCommand;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps the commands the docs, READMEs, javadoc and messages tell a person to run real (#334): MSC installs no
 * {@code msc} executable, so a bare {@code msc <subcommand>} cannot run, and every {@code Main <command>} must be a
 * mode {@link Main} has.
 */
class DocumentedCommandsTest {
    /** A command written as code: after a backtick, quote or {@code {@code }, the word msc, then a subcommand. */
    private static final Pattern BARE_MSC = Pattern.compile("(?:`|'|\"|\\{@code )ms" + "c ([a-z][a-z-]*)");
    /** {@code Main <command> [sub]} as a command line: fully qualified or in backticks. */
    private static final Pattern MAIN_COMMAND = Pattern.compile("(?:core\\.Main|`Main) ([a-z][a-z-]*)(?: ([a-z]+))?");
    /** The modes {@link Main#main} dispatches on. */
    private static final Set<String> MAIN_MODES = Set.of("eula", "refresh-legacy-install");
    /** The files scanned: declared in msc-core/build.gradle.kts, which makes them test inputs too (#355). */
    private static final String SET = "documentedCommands";

    @TempDir
    Path tmp;

    /** The files #334 fixed and #355 found untracked are scanned, so a change to one reruns this test. */
    @Test
    void scansTheTextOfEveryModule() {
        assertThat(RepoFiles.list(SET)).contains("README.md", "CLAUDE.md", "msc-bench/PLAN.md",
                "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/MachineEula.java",
                "msc-templates/src/main/templates/fabric-game/CLAUDE.md", "msc-plugin/.mcp.json",
                "msc-core/build.gradle.kts", "gradle.properties")
                .noneMatch(f -> f.contains("/build/") || f.contains("/test/") || f.matches("(.*/)?\\.[^/]*/.*"));
    }

    @Test
    void noDocumentNamesAnMscExecutable() throws IOException {
        List<String> found = new ArrayList<>();
        for (String f : RepoFiles.list(SET)) {
            scan(f, BARE_MSC).forEach(m -> found.add(f + ": msc " + m.group(1)));
        }
        assertThat(found).as("no `msc` executable is installed; name the line in ~/.msc/core/eula-accept.txt or "
                + "'Main <command>' instead").isEmpty();
    }

    @Test
    void everyDocumentedMainCommandExists() throws IOException {
        List<String> found = new ArrayList<>();
        int seen = 0;
        for (String f : RepoFiles.list(SET)) {
            List<MatchResult> hits = scan(f, MAIN_COMMAND);
            seen += hits.size();
            for (MatchResult m : hits) {
                String mode = m.group(1);
                if (!MAIN_MODES.contains(mode)) {
                    found.add(f + ": Main " + mode);
                } else if (mode.equals("eula") && m.group(2) != null && !eulaKnows(m.group(2))) {
                    found.add(f + ": Main eula " + m.group(2));
                }
            }
        }
        assertThat(seen).as("the docs name the EULA command").isPositive();
        assertThat(found).isEmpty();
    }

    /** Runs the real EULA command non-interactively against a scratch file: it answers usage only for unknown ones. */
    private boolean eulaKnows(String sub) throws IOException {
        StringBuilder out = new StringBuilder();
        EulaCommand.Terminal t = new EulaCommand.Terminal() {
            @Override
            public boolean interactive() {
                return false;
            }

            @Override
            public String readLine(String prompt) {
                return null;
            }

            @Override
            public void println(String line) {
                out.append(line).append('\n');
            }
        };
        EulaCommand.run(List.of(sub), tmp.resolve("eula.json"), null, t, Clock.systemUTC(), "test", "test");
        assertThat(tmp.resolve("eula.json")).doesNotExist();
        return !out.toString().startsWith("usage:");
    }

    private static List<MatchResult> scan(String f, Pattern p) throws IOException {
        try {
            return p.matcher(Files.readString(RepoFiles.path(SET, f))).results().toList();
        } catch (MalformedInputException e) {
            return List.of();
        }
    }
}
