package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The repository is public under Apache-2.0 with a third-party notice (ADR 0115). Every library in the version catalog
 * must be named in THIRD-PARTY-NOTICES.md, so a new dependency comes with its license and where it ends up. Runs in
 * msc-core's repoChecks task.
 */
class LicenseNoticesTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "licensing";
    private static final Pattern MODULE = Pattern.compile("module\\s*=\\s*\"([^\"]+)\"");

    @Test
    void licenseIsApache2() throws IOException {
        assertThat(read("LICENSE")).contains("Apache License", "Version 2.0, January 2004");
        assertThat(read("NOTICE")).contains("Apache License, Version 2.0", "THIRD-PARTY-NOTICES.md", "Mojang");
    }

    @Test
    void gnuTextsForTheLgplJarAreThere() throws IOException {
        assertThat(read("licenses/LGPL-3.0.txt")).contains("GNU LESSER GENERAL PUBLIC LICENSE", "Version 3");
        assertThat(read("licenses/GPL-3.0.txt")).contains("GNU GENERAL PUBLIC LICENSE", "Version 3");
    }

    @Test
    void everyCatalogLibraryIsInTheNotices() throws IOException {
        assertThat(unlisted(read("gradle/libs.versions.toml"), read("THIRD-PARTY-NOTICES.md")))
                .as("catalog libraries missing from THIRD-PARTY-NOTICES.md: add each with its license and where it "
                        + "ends up (ADR 0115)")
                .isEmpty();
    }

    @Test
    void flagsALibraryTheNoticesDoNotName() {
        String catalog = """
                [libraries]
                gson = { module = "com.google.code.gson:gson", version.ref = "gson" }
                h2 = { module = "com.h2database:h2", version.ref = "h2" }
                [plugins]
                shadow = { id = "com.gradleup.shadow", version.ref = "shadow" }
                """;
        assertThat(unlisted(catalog, "| `com.google.code.gson:gson` | Apache-2.0 |")).containsExactly("com.h2database:h2");
        // A bare artifact name or a longer coordinate is not the module.
        assertThat(unlisted(catalog, "`gson`, `com.h2database:h2-extra`")).hasSize(2);
    }

    /** The catalog's library modules that {@code notices} does not name in backticks. */
    static List<String> unlisted(String catalog, String notices) {
        Matcher m = MODULE.matcher(catalog);
        return m.results().map(r -> r.group(1)).distinct().filter(module -> !notices.contains("`" + module + "`"))
                .toList();
    }

    private static String read(String file) throws IOException {
        return Files.readString(RepoFiles.path(SET, file));
    }
}
