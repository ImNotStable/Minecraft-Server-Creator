package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.Platform;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiToolsTest {
    @TempDir
    Path dir;

    @Test
    void theIndexFollowsThePlatformAndANewerJar() throws Exception {
        Path files = dir.resolve("gradle/caches/modules-2/files-2.1");
        Path paper = jar(files.resolve("io.papermc.paper/paper-api/1.0/h/paper-api-1.0.jar"), "org.bukkit.PaperThing");
        jar(files.resolve("net.minestom/minestom/1.0/h/minestom-1.0.jar"), "net.minestom.MinestomThing");
        CoreConfig config = CoreConfig.forWorkspace(dir.resolve("ws"));
        ApiTools api = new ApiTools(config, dir.resolve("gradle"));

        assertThat(api.index().search("thing", null, 5)).contains("org.bukkit.PaperThing");
        Platform.remember(config, Platform.MINESTOM);
        assertThat(api.index().search("thing", null, 5)).contains("net.minestom.MinestomThing").doesNotContain("Paper");

        Platform.remember(config, Platform.PAPER);
        api.index();
        Path newer = jar(files.resolve("io.papermc.paper/paper-api/2.0/h/paper-api-2.0.jar"), "org.bukkit.NewerThing");
        Files.setLastModifiedTime(newer, FileTime.fromMillis(Files.getLastModifiedTime(paper).toMillis() + 60_000));
        assertThat(api.index().search("thing", null, 5)).contains("org.bukkit.NewerThing");
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
