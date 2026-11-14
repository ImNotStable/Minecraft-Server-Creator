package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins who writes the owner's machine-wide Minecraft EULA acceptance, {@code ~/.msc/eula.json} (ADR 0020, #383). Exactly
 * three explicit opt-ins may: a typed yes to {@code Main eula accept} (EulaCommand), {@code installMsc
 * -Pmsc.acceptEula=true} (msc-core/build.gradle.kts) and the first {@code acceptEula=true} on {@code server_setup} or
 * {@code server_start} (EulaPrompts.recordForMachine). Runs in msc-core's repoChecks task over every module's
 * production sources and build scripts.
 */
class EulaWritersTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "eulaWriters";
    private static final String CORE = "msc-core/src/main/java/io/github/imnotstable/msc/core/";
    private static final String MACHINE_EULA = CORE + "server/MachineEula.java";

    /** The documented writers, one entry per call site. Change it only together with ADR 0020 and tools.md. */
    private static final List<String> DOCUMENTED = List.of(
            // 1. Main eula accept, after a typed yes in an interactive terminal.
            CORE + "server/EulaCommand.java: MachineEula.write",
            // 2. installMsc -Pmsc.acceptEula=true writes the file itself.
            "msc-core/build.gradle.kts: writes eula.json",
            // 3. The first acceptEula=true on server_setup or server_start.
            CORE + "tools/EulaPrompts.java: MachineEula.write",
            CORE + "tools/ServerRun.java: recordForMachine",
            CORE + "tools/ServerSetup.java: recordForMachine");

    /** A call or method reference to MachineEula.write, or a static import of it. */
    private static final Pattern WRITE_CALL = Pattern.compile("\\bMachineEula\\s*(?:\\.|::)\\s*write\\b");
    /** Inside MachineEula itself: a bare write( call that is not the declaration. */
    private static final Pattern SELF_WRITE = Pattern.compile("(?<![\\w.:]|void\\s)write\\s*\\(");
    /** A call or method reference to EulaPrompts.recordForMachine, not its declaration. */
    private static final Pattern RECORD_CALL =
            Pattern.compile("(?<![\\w#]|String\\s)recordForMachine\\s*\\(|::\\s*recordForMachine\\b");
    /** "eula.json" as a string literal, not in a comment. */
    private static final Pattern EULA_JSON_LITERAL = Pattern.compile("\"[^\"\\n]*eula\\.json[^\"\\n]*\"");
    /** A file write in a file that names eula.json in a literal, or a write whose target is an eulaFile. */
    private static final Pattern FILE_WRITE = Pattern.compile(
            "\\b(?:writeText|writeBytes|writeString|newBufferedWriter|newOutputStream|FileWriter|Files\\.write)\\b");
    private static final Pattern WRITE_TO_EULA_FILE = Pattern.compile(
            "\\b(?:writeText|writeString|Files\\.write|newBufferedWriter|newOutputStream)\\s*\\([^;]*eulaFile");

    @Test
    void scansEveryModulesProductionCodeAndBuildScripts() {
        assertThat(RepoFiles.list(SET)).contains(MACHINE_EULA, "msc-core/build.gradle.kts",
                        CORE + "tools/EulaPrompts.java",
                        "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/MachineEula.java")
                .noneMatch(f -> f.contains("/build/") || f.startsWith("msc-core/src/test/"));
    }

    @Test
    void onlyTheThreeDocumentedOptInsWriteTheMachineEulaFile() throws IOException {
        List<String> found = new ArrayList<>();
        for (String f : RepoFiles.list(SET)) {
            found.addAll(writers(f, Files.readString(RepoFiles.path(SET, f))));
        }
        assertThat(found).as("the code that writes ~/.msc/eula.json changed. Every writer must be an explicit opt-in "
                        + "by the owner (never a default, never installing or using MSC alone). If this change is "
                        + "meant, update ADR 0020 (msc-docs/docs/adr/0020-machine-eula.md), the EULA step in "
                        + "msc-docs/docs/tools.md and DOCUMENTED in this test")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED);
    }

    @Test
    void detectorFindsEachKindOfWriter() {
        assertThat(writers("a/X.java", "MachineEula.write(f, a, how);\nRunnable r = MachineEula::write;"))
                .containsExactly("a/X.java: MachineEula.write", "a/X.java: MachineEula.write");
        assertThat(writers("a/X.java", "import static x.MachineEula.write;"))
                .containsExactly("a/X.java: MachineEula.write");
        assertThat(writers("a/X.java", "out.append(eula.recordForMachine());\nString recordForMachine() {\n{@link #recordForMachine}"))
                .containsExactly("a/X.java: recordForMachine");
        assertThat(writers("a/b.gradle.kts", "val f = home.resolve(\".msc/eula.json\")\nf.writeText(json)"))
                .containsExactly("a/b.gradle.kts: writes eula.json");
        assertThat(writers("a/X.java", "Files.writeString(config.eulaFile(), json);"))
                .containsExactly("a/X.java: writes eula.json");
        assertThat(writers(MACHINE_EULA, "public static void write(Path file) {}\nvoid other() { write(f, a, h); }"))
                .containsExactly(MACHINE_EULA + ": write");
    }

    @Test
    void detectorIgnoresReadersAndOtherWrites() {
        assertThat(writers("a/X.java", "MachineEula.read(file); MachineEula.writer(); // reads eula.json")).isEmpty();
        assertThat(writers("a/b.gradle.kts", "// eula.json cases shared with msc-core\nf.writeText(\"x\")")).isEmpty();
        assertThat(writers(MACHINE_EULA, "public static void write(Path file, Acceptance a, String how) {\n"
                + "Files.writeString(eulaTxt, \"eula=true\");")).isEmpty();
        assertThat(writers("a/X.java", "Files.writeString(eulaTxt, \"eula=true\"); Path p = config.eulaFile();"))
                .isEmpty();
    }

    /** The writers of the machine EULA file in {@code text}, the source of {@code file}, one entry per site. */
    private static List<String> writers(String file, String text) {
        List<String> out = new ArrayList<>();
        WRITE_CALL.matcher(text).results().forEach(m -> out.add(file + ": MachineEula.write"));
        RECORD_CALL.matcher(text).results().forEach(m -> out.add(file + ": recordForMachine"));
        if (file.equals(MACHINE_EULA)) {
            SELF_WRITE.matcher(text).results().forEach(m -> out.add(file + ": write"));
        } else if ((EULA_JSON_LITERAL.matcher(text).find() && FILE_WRITE.matcher(text).find())
                || WRITE_TO_EULA_FILE.matcher(text).find()) {
            out.add(file + ": writes eula.json");
        }
        return out;
    }
}
