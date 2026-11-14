package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins who can write the owner's machine-wide Minecraft EULA acceptance, {@code ~/.msc/eula.json} (ADR 0020).
 * <p>This is the third and weakest line of ADR 0036. The first is construction: the file's path never leaves
 * {@code MachineEula}, whose one write is {@code recordAcceptance}. The second is {@link EulaOptInBehaviourTest}, which
 * runs every tool and command and looks for the file. This test only keeps plain tokens where they belong, with no
 * data-flow tracking: the file name {@code eula.json} appears in production code (comments left out) only in the two
 * {@code MachineEula} classes and installMsc's own opt-in, and the two calls that write it appear only at the
 * documented opt-ins. Runs in msc-core's repoChecks task over every module's production sources and build scripts.
 */
class EulaWritersTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "eulaWriters";
    private static final String CORE = "msc-core/src/main/java/io/github/imnotstable/msc/core/";
    private static final String MACHINE_EULA = CORE + "server/MachineEula.java";

    /**
     * Where each token may appear, one entry per occurrence. Change it only together with ADR 0020 and tools.md: each
     * entry is the owner's explicit opt-in or the one class that owns the file.
     */
    private static final List<String> DOCUMENTED = List.of(
            // The file name: the class that owns the path, the bench's read-only copy, and installMsc's opt-in.
            MACHINE_EULA + ": eula.json",
            "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/MachineEula.java: eula.json",
            // 2. installMsc -Pmsc.acceptEula=true writes the file itself (a build script cannot use MachineEula).
            "msc-core/build.gradle.kts: eula.json",
            // The one write: its declaration, 1. Main eula accept after a typed yes, 3. acceptEula=true on a tool.
            MACHINE_EULA + ": recordAcceptance",
            CORE + "server/EulaCommand.java: recordAcceptance",
            CORE + "tools/EulaPrompts.java: recordAcceptance",
            // 3. The first acceptEula=true on server_setup or server_start: the declaration and its two callers.
            CORE + "tools/EulaPrompts.java: recordForMachine",
            CORE + "tools/ServerRun.java: recordForMachine",
            CORE + "tools/ServerSetup.java: recordForMachine");

    /** The tokens, matched in code with comments blanked; the file name is matched inside string literals too. */
    private static final Map<String, Pattern> TOKENS = Map.of(
            "eula.json", Pattern.compile("eula\\.json"),
            "recordAcceptance", Pattern.compile("\\brecordAcceptance\\b"),
            "recordForMachine", Pattern.compile("\\brecordForMachine\\b"));

    /** The one write MachineEula may do, whitespace removed: the acceptance JSON into its own file. */
    private static final String RECORD_BODY = "if(file==null){thrownewIllegalStateException(\"nomachineEULAfiletowrite\");}"
            + "JsonObjecto=newJsonObject();o.addProperty(\"eula\",EULA_URL);"
            + "o.addProperty(\"accepted\",true);o.addProperty(\"acceptedBy\",a.user());"
            + "o.addProperty(\"host\",a.host());o.addProperty(\"acceptedAt\",a.acceptedAt().toString());"
            + "o.addProperty(\"how\",how);Files.createDirectories(file.toAbsolutePath().getParent());"
            + "Files.writeString(file,GSON.toJson(o)+\"\\n\");";

    @Test
    void scansEveryModulesProductionCodeAndBuildScripts() {
        assertThat(RepoFiles.list(SET)).contains(MACHINE_EULA, "msc-core/build.gradle.kts",
                        CORE + "tools/EulaPrompts.java",
                        "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/MachineEula.java")
                .noneMatch(f -> f.contains("/build/") || f.startsWith("msc-core/src/test/"));
    }

    @Test
    void theFileNameAndItsWritersAppearOnlyAtTheDocumentedPlaces() throws IOException {
        List<String> found = new ArrayList<>();
        for (String f : RepoFiles.list(SET)) {
            found.addAll(occurrences(f, Files.readString(RepoFiles.path(SET, f))));
        }
        assertThat(found).as("the machine EULA file (~/.msc/eula.json) is now named, or written, somewhere new. "
                        + "Every write must be an explicit opt-in by the owner (never a default, never installing or "
                        + "using MSC alone). Use a MachineEula object (CoreConfig.machineEula()): read(), applyTo(), "
                        + "and recordAcceptance() only from a documented opt-in; to show the file to the user, print "
                        + "the object. If a new opt-in is meant, update ADR 0020, the EULA step in "
                        + "msc-docs/docs/tools.md and DOCUMENTED here (ADR 0036)")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED);
    }

    @Test
    void theOneWriteDoesOnlyWhatItDid() throws IOException {
        // #397: recordAcceptance is the one place every documented opt-in goes through; its body is pinned.
        assertThat(body(SourceText.mask(Files.readString(RepoFiles.path(SET, MACHINE_EULA)), false),
                "void recordAcceptance(").replaceAll("\\s+", "")).isEqualTo(RECORD_BODY);
    }

    @Test
    void reportsEachTokenOutsideComments() {
        assertThat(occurrences("a/X.java", "Files.writeString(Path.of(home, \".msc\", \"eula.json\"), json);"))
                .containsExactly("a/X.java: eula.json");
        assertThat(occurrences("a/b.gradle.kts", "File(h).resolve(\".msc/eula.json\").writeText(json)"))
                .containsExactly("a/b.gradle.kts: eula.json");
        assertThat(occurrences("a/X.java", "config.machineEula().recordAcceptance(a, how);"))
                .containsExactly("a/X.java: recordAcceptance");
        assertThat(occurrences("a/X.java", "out.append(eula.recordForMachine());\nRunnable r = p::recordForMachine;"))
                .containsExactly("a/X.java: recordForMachine", "a/X.java: recordForMachine");
    }

    @Test
    void ignoresCommentsAndOtherNames() {
        assertThat(occurrences("a/X.java", """
                /** Reads {@code ~/.msc/eula.json}; see {@link MachineEula#recordAcceptance}. */
                // recordForMachine() writes eula.json
                Path eulaTxt = dir.resolve("eula.txt");
                boolean eulaJson = recordAcceptances(eulaJsonFile);""")).isEmpty();
    }

    /** One entry per token in {@code text}, the source of {@code file}, comments left out. */
    private static List<String> occurrences(String file, String text) {
        String code = SourceText.mask(text, false);
        List<String> out = new ArrayList<>();
        TOKENS.forEach((name, token) -> token.matcher(code).results().forEach(m -> out.add(file + ": " + name)));
        return out;
    }

    /** The body of the method whose declaration contains {@code declaration}, braces excluded, or "". */
    private static String body(String code, String declaration) {
        int at = code.indexOf(declaration);
        int open = at < 0 ? -1 : code.indexOf('{', at);
        if (open < 0) {
            return "";
        }
        int close = SourceText.pairs(SourceText.mask(code, true), '{', '}')[open];
        return code.substring(open + 1, close);
    }
}
