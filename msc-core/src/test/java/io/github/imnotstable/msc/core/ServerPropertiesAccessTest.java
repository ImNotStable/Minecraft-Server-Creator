package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins that a Minecraft server's {@code server.properties} is read and written only by msc-process's
 * {@code ServerPropertiesFile}, which reads and escapes it as the server does (ADR 0033, #391, #392). Hand-rolled
 * readers and writers had drifted on charset and escaping. Runs in msc-core's repoChecks task over every module's
 * production sources.
 */
class ServerPropertiesAccessTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "productionSources";
    private static final String SHARED =
            "msc-process/src/main/java/io/github/imnotstable/msc/process/ServerPropertiesFile.java";

    /**
     * The java.util.Properties users that read other files, one entry per site. A new one that reads a server's
     * server.properties must use ServerPropertiesFile instead; one for another file is added here.
     */
    private static final List<String> OTHER_PROPERTIES_FILES = List.of(
            // version.properties, a resource of msc-core's own jar.
            "msc-core/src/main/java/io/github/imnotstable/msc/core/Core.java: java.util.Properties",
            // install.properties, written by installMsc.
            "msc-core/src/main/java/io/github/imnotstable/msc/core/LegacyInstall.java: java.util.Properties",
            "msc-core/src/main/java/io/github/imnotstable/msc/core/SetupCheck.java: java.util.Properties",
            "msc-core/src/main/java/io/github/imnotstable/msc/core/SetupCheck.java: java.util.Properties",
            // .msc/deployed.properties, dev_cycle's own record.
            "msc-core/src/main/java/io/github/imnotstable/msc/core/tools/DevCycle.java: java.util.Properties",
            // template.properties and versions.properties, resources of msc-templates.
            "msc-templates/src/main/java/io/github/imnotstable/msc/templates/Templates.java: java.util.Properties",
            "msc-templates/src/main/java/io/github/imnotstable/msc/templates/Templates.java: java.util.Properties");

    private static final Pattern NEW_PROPERTIES = Pattern.compile("\\bnew\\s+(?:java\\.util\\.)?Properties\\s*\\(");
    /** Reading a file's text a line or a reader at a time. */
    private static final Pattern TEXT_READ = Pattern.compile(
            "\\b(?:readAllLines|readString|newBufferedReader|lines|FileReader)\\s*\\(");
    /** Splitting key=value lines by hand. */
    private static final Pattern KEY_VALUE_SPLIT = Pattern.compile(
            "indexOf\\s*\\(\\s*'[=:]'|indexOf\\s*\\(\\s*\"[=:]\"|split\\s*\\(\\s*\"[=:]\"");
    /** A call whose first argument is the file it reads or writes as text or a stream. */
    private static final Pattern FILE_CALL = Pattern.compile("\\b(?:Files\\s*\\.\\s*(?:readAllLines|readString|lines"
            + "|newBufferedReader|newInputStream|writeString|write|newBufferedWriter|newOutputStream)"
            + "|new\\s+(?:FileReader|FileWriter|FileInputStream|FileOutputStream))\\s*\\(");
    /** Code that names the file. */
    private static final Pattern NAMES_FILE = Pattern.compile("\"[^\"\\n]*server\\.properties[^\"\\n]*\"|ServerPropertiesFile\\s*\\.\\s*NAME");
    /** A local or field assigned on one line. */
    private static final Pattern ASSIGNMENT = Pattern.compile("\\b(\\w+)\\s*=(?!=)([^;\\n]*)");

    @Test
    void scansEveryModulesProductionCode() {
        assertThat(RepoFiles.list(SET)).contains(SHARED,
                        "msc-core/src/main/java/io/github/imnotstable/msc/core/server/RconTarget.java",
                        "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/Workspaces.java")
                .noneMatch(f -> f.contains("/build/") || f.contains("/src/test/") || f.contains("/src/main/templates/"));
    }

    @Test
    void onlyServerPropertiesFileReadsOrWritesServerProperties() throws IOException {
        List<String> found = new ArrayList<>();
        for (String f : RepoFiles.list(SET)) {
            if (!f.equals(SHARED)) {
                found.addAll(handRolled(f, Files.readString(RepoFiles.path(SET, f))));
            }
        }
        assertThat(found).as("server.properties is read and written only through msc-process's ServerPropertiesFile "
                        + "(ADR 0033), which matches the server's charset and escaping. Use it; a Properties user "
                        + "for another file goes into OTHER_PROPERTIES_FILES in this test")
                .containsExactlyInAnyOrderElementsOf(OTHER_PROPERTIES_FILES);
    }

    @Test
    void detectorFindsTheHandRolledReadersAndWritersThisReplaced() {
        // RconTarget.of before #391.
        assertThat(handRolled("a/X.java", """
                Path file = serverDir.resolve("server.properties");
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(file)) { p.load(in); }"""))
                .containsExactly("a/X.java: java.util.Properties", "a/X.java: opens server.properties");
        // ServerProperties.set and Workspaces.setProperties before #392.
        assertThat(handRolled("a/X.java", """
                for (String line : Files.readAllLines(file)) {
                    int eq = line.indexOf('=');"""))
                .containsExactly("a/X.java: key=value lines");
        assertThat(handRolled("a/X.java", "Files.writeString(dir.resolve(ServerPropertiesFile.NAME), \"motd=x\");"))
                .containsExactly("a/X.java: opens server.properties");
        assertThat(handRolled("a/X.java", "try (Writer w = Files.newBufferedWriter(dir.resolve(\"server.properties\"))) {}"))
                .containsExactly("a/X.java: opens server.properties");
    }

    @Test
    void detectorIgnoresTheSharedClassesCallersAndOtherFiles() {
        assertThat(handRolled("a/X.java", """
                // reads server.properties: Files.readAllLines(file) and line.indexOf('=')
                ServerPropertiesFile.set(dir.resolve(ServerPropertiesFile.NAME), values);
                byte[] snapshot = Files.readAllBytes(dir.resolve("server.properties"));
                Files.writeString(server.resolve("eula.txt"), "eula=true");""")).isEmpty();
        assertThat(handRolled("a/X.java", "String s = Files.readString(file); int i = s.indexOf(' ');")).isEmpty();
    }

    /** The hand-rolled server.properties access in {@code text}, the source of {@code file}, one entry per site. */
    private static List<String> handRolled(String file, String text) {
        String code = withoutComments(text);
        List<String> out = new ArrayList<>();
        NEW_PROPERTIES.matcher(code).results().forEach(m -> out.add(file + ": java.util.Properties"));
        if (TEXT_READ.matcher(code).find() && KEY_VALUE_SPLIT.matcher(code).find()) {
            out.add(file + ": key=value lines");
        }
        List<String> named = new ArrayList<>();
        ASSIGNMENT.matcher(code).results().filter(m -> NAMES_FILE.matcher(m.group(2)).find())
                .forEach(m -> named.add(m.group(1)));
        Matcher call = FILE_CALL.matcher(code);
        while (call.find()) {
            String target = firstArgument(code, call.end());
            if (NAMES_FILE.matcher(target).find()
                    || named.stream().anyMatch(v -> Pattern.compile("\\b" + v + "\\b").matcher(target).find())) {
                out.add(file + ": opens server.properties");
            }
        }
        return out;
    }

    /** The text from {@code from} to the first comma or closing parenthesis outside brackets and literals. */
    private static String firstArgument(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                i = closingQuote(code, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && depth-- == 0 || c == ',' && depth == 0) {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
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

    /** {@code code} with its line and block comments left out, literals kept. */
    private static String withoutComments(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                int end = Math.min(closingQuote(code, i), code.length() - 1);
                sb.append(code, i, end + 1);
                i = end;
            } else if (c == '\'' && i + 1 < code.length() && code.charAt(i + 1) == '\\') {
                int end = code.indexOf('\'', i + 3); // an escaped char literal such as '\"'
                end = end < 0 ? code.length() - 1 : end;
                sb.append(code, i, end + 1);
                i = end;
            } else if (c == '\'' && i + 2 < code.length() && code.charAt(i + 2) == '\'') {
                sb.append(code, i, i + 3); // a char literal such as '"' must not open a string
                i += 2;
            } else if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                i = (end < 0 ? code.length() : end) - 1;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
