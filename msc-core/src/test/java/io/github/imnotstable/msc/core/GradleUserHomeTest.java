package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.server.GradleUserHome;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #666: the core reads the API jars from the Gradle user home its own builds use, which {@code GRADLE_USER_HOME} (or the
 * {@code gradle.user.home} system property) moves away from {@code ~/.gradle}.
 */
class GradleUserHomeTest {
    @TempDir
    Path dir;

    @Test
    void gradlesOwnRuleChoosesTheHome() {
        Path home = dir.resolve("home");
        Path env = dir.resolve("env");
        Path property = dir.resolve("property");
        Map<String, String> set = Map.of(GradleUserHome.ENV, env.toString());
        assertThat(GradleUserHome.resolve(Map.of(), null, home.toString())).isEqualTo(home.resolve(".gradle"));
        assertThat(GradleUserHome.resolve(Map.of(GradleUserHome.ENV, " "), "", home.toString()))
                .isEqualTo(home.resolve(".gradle"));
        assertThat(GradleUserHome.resolve(set, null, home.toString())).isEqualTo(env);
        assertThat(GradleUserHome.resolve(set, property.toString(), home.toString())).isEqualTo(property);
        assertThat(GradleUserHome.resolve(Map.of(GradleUserHome.ENV, "rel"), null, home.toString()))
                .isEqualTo(Path.of("rel").toAbsolutePath());

        String own = System.getProperty(GradleUserHome.PROPERTY);
        CoreConfig config = CoreConfig.fromEnvironment(Map.of(CoreConfig.ENV_WORKSPACE, dir.toString(),
                GradleUserHome.ENV, env.toString()));
        assertThat(config.gradleUserHome()).isEqualTo(own == null ? env : Path.of(own).toAbsolutePath().normalize());
    }

    @Test
    void apiSearchFindsTheJarsTheBuildDownloadedIntoGradleUserHome() throws Exception {
        Path workspace = Files.createDirectories(dir.resolve("ws"));
        Path project = Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        Path userHome = Files.createDirectories(dir.resolve("user"));
        Path gradleHome = dir.resolve("gradle-elsewhere");
        Path home = GradleUserHome.resolve(Map.of(GradleUserHome.ENV, gradleHome.toString()), null, userHome.toString());
        // What Gradle downloads into its caches when the project builds.
        Path api = jar(dir.resolve("download/paper-api-1.0.jar"), "org.bukkit.GizmoThing");
        Files.writeString(project.resolve("download.txt"),
                api + "\ncaches/modules-2/files-2.1/io.papermc.paper/paper-api/1.0/h/paper-api-1.0.jar\n");
        List<String> build = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeBuildMain.class.getName());
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withBuildCommand(build).withGradleUserHome(home))) {
            assertThat(h.error("api_search", "query", "gizmo"))
                    .contains("no paper API jar found in the Gradle caches of " + gradleHome);
            assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("deployed demo-1.0.jar");
            assertThat(h.ok("api_search", "query", "gizmo")).contains("org.bukkit.GizmoThing");
            assertThat(h.ok("api_class", "name", "GizmoThing")).contains("org.bukkit.GizmoThing");
        }
        assertThat(userHome.resolve(".gradle")).doesNotExist();
    }

    private static Path jar(Path jar, String className) throws Exception {
        Files.createDirectories(jar.getParent());
        byte[] bytes = ClassFile.of().build(ClassDesc.of(className), cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
            zip.write(bytes);
            zip.closeEntry();
        }
        return jar;
    }
}
