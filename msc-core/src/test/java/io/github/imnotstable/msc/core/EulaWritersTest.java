package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins who writes the owner's machine-wide Minecraft EULA acceptance, {@code ~/.msc/eula.json} (ADR 0020, #383). Exactly
 * three explicit opt-ins may: a typed yes to {@code Main eula accept} (EulaCommand), {@code installMsc
 * -Pmsc.acceptEula=true} (msc-core/build.gradle.kts) and the first {@code acceptEula=true} on {@code server_setup} or
 * {@code server_start} (EulaPrompts.recordForMachine). Runs in msc-core's repoChecks task over every module's
 * production sources and build scripts.
 * <p>A tripwire for ordinary changes, not a proof against deliberate evasion (ADR 0035): it follows the JDK and
 * msc-core calls that write a file ({@code Files}, {@code java.io} writers, {@code AtomicFile}, {@code OutputFile}, a
 * copy or move to or from the file, Kotlin's file writers) to a path that names the file, directly or through a local
 * or field. A name built at run time or reflection is out of its reach. The tables in
 * {@link #detectorFindsEachKindOfWriter} and {@link #detectorIgnoresReadersAndOtherWrites} show its reach.
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
    /** Any string literal. */
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    /**
     * A call whose first argument is the file it writes: a JDK writer, or msc-core's AtomicFile and OutputFile (by
     * simple name, wherever the class lives), whose writer lambda writes a temp file that then replaces the target.
     */
    private static final Pattern WRITE_TO_ARGUMENT = Pattern.compile("\\b(?:Files\\s*\\.\\s*(?:writeString|write"
            + "|newBufferedWriter|newOutputStream)|new\\s+(?:FileWriter|FileOutputStream|PrintWriter"
            + "|RandomAccessFile)|(?:AtomicFile|OutputFile)\\s*\\.\\s*(?:write|replace))\\s*\\(");
    /** A copy or move: its source and its target both count, as either carries the acceptance to a new place. */
    private static final Pattern COPY_OR_MOVE = Pattern.compile(
            "\\b(?:Files|AtomicFile|OutputFile)\\s*\\.\\s*(?:copy|move)\\s*\\(");
    /** A Kotlin call whose receiver is the file it writes. */
    private static final Pattern WRITE_TO_RECEIVER = Pattern.compile(
            "\\.\\s*(?:writeText|writeBytes|appendText|appendBytes|outputStream|bufferedWriter|printWriter|writer)\\s*\\(");
    /** A Kotlin copy or rename: the receiver and the first argument both count. */
    private static final Pattern COPY_RECEIVER = Pattern.compile("\\.\\s*(?:copyTo|renameTo)\\s*\\(");
    /** Code that names the machine EULA file: its helpers, or the file name. */
    private static final Pattern NAMES_EULA_FILE = Pattern.compile(
            "\\beulaFile\\s*\\(|\\bdefaultFile\\s*\\(|\\bMachineEula\\s*\\.\\s*file\\s*\\(|eula\\.json");
    /** A local or field assigned from code that names the machine EULA file. */
    private static final Pattern TAINTED = Pattern.compile("\\b(\\w+)\\s*=(?!=)([^;\\n]*)");
    /** The one write MachineEula.write may do, whitespace removed: the acceptance JSON into its file argument. */
    private static final String MACHINE_EULA_WRITE_BODY = "JsonObjecto=newJsonObject();o.addProperty(\"eula\",EULA_URL);"
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
    void onlyTheThreeDocumentedOptInsWriteTheMachineEulaFile() throws IOException {
        List<String> found = new ArrayList<>();
        for (String f : RepoFiles.list(SET)) {
            found.addAll(writers(f, Files.readString(RepoFiles.path(SET, f))));
        }
        assertThat(found).as("the code that writes ~/.msc/eula.json changed. Every writer must be an explicit opt-in "
                        + "by the owner (never a default, never installing or using MSC alone, never an acceptance "
                        + "in a tracked file). Write it only through MachineEula.write from one of the documented "
                        + "opt-ins. If this change is meant, update ADR 0020 "
                        + "(msc-docs/docs/adr/0020-machine-eula.md), the EULA step in msc-docs/docs/tools.md and "
                        + "DOCUMENTED in this test. If an entry is not a write of that file, the path was taken for "
                        + "it: rename the variable or add the shape to detectorIgnoresReadersAndOtherWrites (ADR 0035)")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED);
    }

    @Test
    void machineEulaWriteDoesOnlyWhatItDid() throws IOException {
        // #397: MachineEula.write is the one place every documented opt-in goes through; its body is pinned.
        assertThat(body(SourceText.mask(Files.readString(RepoFiles.path(SET, MACHINE_EULA)), false), "void write(")
                .replaceAll("\\s+", "")).isEqualTo(MACHINE_EULA_WRITE_BODY);
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
        // #397: each write counts, not each file; a write in MachineEula outside write(); a path held in a variable
        // or built by a helper.
        assertThat(writers("a/b.gradle.kts", "val f = home.resolve(\".msc/eula.json\")\nf.writeText(json)\n"
                + "rootDir.resolve(\"eula-accept.txt\").writeText(cmd)\nf.writeText(again)"))
                .containsExactly("a/b.gradle.kts: writes eula.json", "a/b.gradle.kts: writes eula.json");
        assertThat(writers(MACHINE_EULA, "static Optional<Acceptance> read(Path file) {\n"
                + "if (!Files.exists(file)) { Files.writeString(file, \"{accepted:true}\"); }\n}"))
                .containsExactly(MACHINE_EULA + ": writes eula.json");
        assertThat(writers("a/X.java", "Path f = config.eulaFile();\nFiles.writeString(f, json);"))
                .containsExactly("a/X.java: writes eula.json");
        assertThat(writers("a/X.java", "Files.writeString(MachineEula.defaultFile(home), json);"))
                .containsExactly("a/X.java: writes eula.json");
        assertThat(writers("a/X.java", "Path p = MachineEula.file(home);\ntry (var w = Files.newBufferedWriter(p)) {}"))
                .containsExactly("a/X.java: writes eula.json");
        assertThat(writers("a/X.java", "String name = \"eula.json\";\nFiles.write(dir.resolve(name), bytes);"))
                .containsExactly("a/X.java: writes eula.json");
        // #410: msc-core's own atomic writers, wherever AtomicFile lives, and a copy or move to or from the file.
        for (String write : List.of("AtomicFile.write(config.eulaFile(), to -> Files.writeString(to, json));",
                "io.github.imnotstable.msc.process.AtomicFile.replace(MachineEula.file(home), w);",
                "OutputFile.write(config.eulaFile(), to -> Files.writeString(to, json));",
                "OutputFile.replace(\n    config.eulaFile(),\n    to -> Files.write(to, bytes));",
                "Path f = config.eulaFile();\nAtomicFile.write(f, w);",
                "Files.copy(staged, MachineEula.defaultFile(home), StandardCopyOption.REPLACE_EXISTING);",
                "Files.move(temp, config.eulaFile(), StandardCopyOption.ATOMIC_MOVE);",
                "Files.copy(config.eulaFile(), server.resolve(\"eula-copy.json\"));",
                "Files.move(config.eulaFile(), backup);",
                "AtomicFile.copy(staged, config.eulaFile());",
                "OutputFile.copy(config.eulaFile(), target);",
                "Files.copy(in, home.resolve(\".msc/eula.json\"));")) {
            assertThat(writers("a/X.java", write)).as(write).containsExactly("a/X.java: writes eula.json");
        }
        assertThat(writers("a/b.gradle.kts", "val f = home.resolve(\".msc/eula.json\")\nstaged.copyTo(f, true)\n"
                + "f.renameTo(old)")).containsExactly("a/b.gradle.kts: writes eula.json", "a/b.gradle.kts: writes eula.json");
    }

    @Test
    void detectorIgnoresReadersAndOtherWrites() {
        assertThat(writers("a/X.java", "MachineEula.read(file); MachineEula.writer(); // reads eula.json")).isEmpty();
        assertThat(writers("a/b.gradle.kts", "// eula.json cases shared with msc-core\nf.writeText(\"x\")")).isEmpty();
        assertThat(writers(MACHINE_EULA, "public static void write(Path file, Acceptance a, String how) {\n"
                + "Files.writeString(file, json);\n}\nstatic void applyTo(Path file, Path eulaTxt) {\n"
                + "Files.writeString(eulaTxt, \"eula=true\");\n}")).isEmpty();
        assertThat(writers("a/X.java", "Files.writeString(eulaTxt, \"eula=true\"); Path p = config.eulaFile();"))
                .isEmpty();
        assertThat(writers("a/b.gradle.kts", "val f = home.resolve(\".msc/eula.json\")\n"
                + "rootDir.resolve(\"eula-accept.txt\").writeText(cmd)\nmarker.get().asFile.writeText(\"ok\")")).isEmpty();
        assertThat(writers("a/X.java", "if (MachineEula.read(config.eulaFile()).isPresent()) {}\n"
                + "Files.writeString(file, Files.readString(file).replace(\"eula=false\", \"eula=true\"));")).isEmpty();
        // #410: atomic writes, copies and moves of other files, and the file's name only in a string or a comment.
        assertThat(writers("a/X.java", """
                AtomicFile.write(zipFile(), to -> Files.write(to, data));
                OutputFile.write(properties, to -> Files.write(to, offeredBefore));
                Files.copy(jar, plugins.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                // Files.copy(staged, config.eulaFile())
                String hint = "AtomicFile.write(config.eulaFile(), w)";""")).isEmpty();
        assertThat(writers("a/b.gradle.kts", "val f = home.resolve(\".msc/eula.json\")\njar.copyTo(dest)")).isEmpty();
    }

    /** The writers of the machine EULA file in {@code text}, the source of {@code file}, one entry per site. */
    private static List<String> writers(String file, String text) {
        String code = SourceText.mask(text, false);
        String bare = SourceText.mask(text, true);
        List<String> out = new ArrayList<>();
        WRITE_CALL.matcher(code).results().forEach(m -> out.add(file + ": MachineEula.write"));
        RECORD_CALL.matcher(code).results().forEach(m -> out.add(file + ": recordForMachine"));
        String writeBody = "";
        if (file.equals(MACHINE_EULA)) {
            SELF_WRITE.matcher(code).results().forEach(m -> out.add(file + ": write"));
            writeBody = body(code, "void write(");
        }
        List<String> tainted = new ArrayList<>();
        TAINTED.matcher(code).results().filter(m -> NAMES_EULA_FILE.matcher(m.group(2)).find())
                .forEach(m -> tainted.add(m.group(1)));
        for (String target : writeTargets(code, bare, writeBody)) {
            boolean otherFile = LITERAL.matcher(target).results().map(m -> m.group())
                    .anyMatch(l -> !l.contains("eula.json"));
            boolean eulaTxt = file.equals(MACHINE_EULA) && target.strip().equals("eulaTxt");
            boolean named = NAMES_EULA_FILE.matcher(target).find()
                    || tainted.stream().anyMatch(v -> Pattern.compile("\\b" + v + "\\b").matcher(target).find());
            // In a MachineEula class, the owner of the file, any write is taken to be to it unless it names another.
            boolean owner = file.endsWith("/MachineEula.java");
            if (named || owner && !otherFile && !eulaTxt) {
                out.add(file + ": writes eula.json");
            }
        }
        return out;
    }

    /** The file each write call in {@code code} writes to, as source text; writes inside {@code skip} are left out. */
    private static List<String> writeTargets(String code, String bare, String skip) {
        int skipFrom = skip.isEmpty() ? -1 : code.indexOf(skip);
        int skipTo = skipFrom < 0 ? -1 : skipFrom + skip.length();
        List<String> out = new ArrayList<>();
        for (Pattern p : List.of(WRITE_TO_ARGUMENT, COPY_OR_MOVE, WRITE_TO_RECEIVER, COPY_RECEIVER)) {
            for (MatchResult m : p.matcher(bare).results().toList()) {
                if (m.start() >= skipFrom && m.start() < skipTo) {
                    continue;
                }
                List<int[]> args = SourceText.arguments(bare, m.end() - 1);
                int count = p == COPY_OR_MOVE ? 2 : p == WRITE_TO_ARGUMENT || p == COPY_RECEIVER ? 1 : 0;
                args.stream().limit(count).forEach(a -> out.add(code.substring(a[0], a[1])));
                if (p == WRITE_TO_RECEIVER || p == COPY_RECEIVER) {
                    out.add(receiver(code, m.start()));
                }
            }
        }
        return out;
    }

    /** The receiver expression that ends just before {@code dot}: names, dots, calls and literals. */
    private static String receiver(String code, int dot) {
        int i = dot - 1;
        while (i >= 0) {
            char c = code.charAt(i);
            if (c == ')') {
                int depth = 0;
                for (; i >= 0; i--) {
                    char d = code.charAt(i);
                    depth += d == ')' ? 1 : d == '(' ? -1 : 0;
                    if (depth == 0) {
                        break;
                    }
                }
                i--;
            } else if (Character.isJavaIdentifierPart(c) || c == '.') {
                i--;
            } else {
                break;
            }
        }
        return code.substring(i + 1, dot);
    }

    private static int closingQuote(String code, int open) {
        for (int i = open + 1; i < code.length(); i++) {
            if (code.charAt(i) == '\\') {
                i++;
            } else if (code.charAt(i) == '"' || code.charAt(i) == '\n') {
                return i;
            }
        }
        return code.length();
    }

    /** The body of the method whose declaration contains {@code declaration}, braces excluded, or "". */
    private static String body(String code, String declaration) {
        int at = code.indexOf(declaration);
        int open = at < 0 ? -1 : code.indexOf('{', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                i = closingQuote(code, i);
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open + 1, i);
            }
        }
        return code.substring(open + 1);
    }
}
