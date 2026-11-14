package io.github.imnotstable.msc.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #399: a file written whole keeps what a plain write would have given it; #398: "same file" is the file system's call. */
class AtomicFileTest {
    @TempDir
    Path dir;

    private static void assumePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
    }

    /** The permissions a plain {@code Files.write} gives a new file here: 0666 minus the umask. */
    private Set<PosixFilePermission> plainNew() throws IOException {
        Path probe = Files.createFile(dir.resolve("probe-" + System.nanoTime()));
        try {
            return Files.getPosixFilePermissions(probe);
        } finally {
            Files.delete(probe);
        }
    }

    private static Set<PosixFilePermission> mode(String text) {
        return PosixFilePermissions.fromString(text);
    }

    /** Ways a writer fills the temp: in place, by recreating it (as Files.copy and ImageIO do), and the copy itself. */
    private static final List<String> WRITERS = List.of("write", "write-recreating", "replace", "copy");

    private Path put(String how, Path target, String content) throws IOException {
        return switch (how) {
            case "write" -> AtomicFile.write(target, to -> Files.writeString(to, content));
            case "write-recreating" -> AtomicFile.write(target, to -> {
                Files.delete(to);
                Files.writeString(to, content);
            });
            case "replace" -> AtomicFile.replace(target, to -> Files.writeString(to, content));
            case "copy" -> AtomicFile.copy(Files.writeString(dir.resolve("source-" + System.nanoTime()), content), target);
            default -> throw new IllegalArgumentException(how);
        };
    }

    @Test
    void anExistingFileKeepsItsPermissionsWhateverTheWriterDoes() throws IOException {
        assumePosix();
        for (String how : WRITERS) {
            for (String was : List.of("rw-rw-r--", "rw-r-----", "rw----r--", "rwxr-xr-x")) {
                Path file = Files.writeString(dir.resolve(how + "-" + was + ".txt"), "old");
                Files.setPosixFilePermissions(file, mode(was));
                put(how, file, "new");
                assertThat(file).as("%s over %s", how, was).hasContent("new");
                assertThat(Files.getPosixFilePermissions(file)).as("%s over %s", how, was).isEqualTo(mode(was));
            }
        }
        assertNoParts();
    }

    @Test
    void aNewFileGetsWhatAPlainWriteGivesNotTheTempFiles0600() throws IOException {
        assumePosix();
        Set<PosixFilePermission> plain = plainNew();
        for (String how : List.of("write", "write-recreating", "replace")) {
            Path file = put(how, dir.resolve("sub/" + how + ".txt"), "new");
            assertThat(Files.getPosixFilePermissions(file)).as(how).isEqualTo(plain);
        }
        // A copy gets what Files.copy gave before #388: the source's mode, minus the umask.
        Path source = Files.writeString(dir.resolve("exec.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(source, mode("rwxr-x---"));
        Path before = Files.copy(source, dir.resolve("plain-copy.sh"));
        Path copied = AtomicFile.copy(source, dir.resolve("atomic-copy.sh"));
        assertThat(Files.getPosixFilePermissions(copied)).isEqualTo(Files.getPosixFilePermissions(before));
        assertNoParts();
    }

    @Test
    void writeFollowsALinkAsAnInPlaceWriteDidAndReplaceReplacesIt() throws IOException {
        Path real = Files.writeString(dir.resolve("real.properties"), "old");
        Path link;
        try {
            link = Files.createSymbolicLink(dir.resolve("link.properties"), real.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links: " + e.getMessage());
            return;
        }
        AtomicFile.write(link, to -> Files.writeString(to, "via link"));
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(real).hasContent("via link");

        AtomicFile.replace(link, to -> Files.writeString(to, "replaced"));
        assertThat(Files.isSymbolicLink(link)).isFalse();
        assertThat(link).hasContent("replaced");
        assertThat(real).hasContent("via link");
        assertNoParts();
    }

    @Test
    void aFailedWriteLeavesTheFileAsItWasAndNoTemp() throws IOException {
        Path file = Files.writeString(dir.resolve("kept.txt"), "old");
        assertThatThrownBy(() -> AtomicFile.write(file, to -> {
            Files.writeString(to, "half");
            throw new IOException("disk full");
        })).hasMessage("disk full");
        assertThat(file).hasContent("old");
        assertNoParts();
    }

    @Test
    void sameFileIsTheFileSystemsAnswerNotThePaths() throws IOException {
        Path jar = Files.writeString(Files.createDirectories(dir.resolve("plugins")).resolve("lobby.jar"), "x");
        assertThat(AtomicFile.sameFile(jar, jar)).isTrue();
        assertThat(AtomicFile.sameFile(jar, dir.resolve("plugins/./lobby.jar"))).isTrue();
        Path other = Files.writeString(dir.resolve("plugins/arena.jar"), "x");
        assertThat(AtomicFile.sameFile(jar, other)).as("same bytes, another file").isFalse();
        assertThat(AtomicFile.sameFile(dir.resolve("plugins/missing.jar"), jar)).as("no file").isFalse();
        assertThat(AtomicFile.sameFile(jar, dir.resolve("plugins/missing.jar"))).isFalse();
    }

    @Test
    void anotherSpellingOfOneFileIsTheSameFile() throws IOException {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path jar = Files.writeString(plugins.resolve("lobby.jar"), "x");
        // A case-insensitive volume names lobby.jar as Lobby.jar too. Where this one is case-sensitive, a path through
        // a linked folder is another spelling of the same directory entry, as Lobby.jar is there.
        Path upper = plugins.resolve("Lobby.jar");
        if (Files.exists(upper)) {
            assertThat(AtomicFile.sameFile(upper, jar)).as("case-insensitive volume").isTrue();
        } else {
            Files.writeString(upper, "y");
            assertThat(AtomicFile.sameFile(upper, jar)).as("case-sensitive volume: two files").isFalse();
        }
        Path alias = alias(plugins);
        assertThat(AtomicFile.sameFile(alias.resolve("lobby.jar"), jar)).isTrue();
        // A link to the file is another entry: deleting it leaves the file.
        Path link = Files.createSymbolicLink(plugins.resolve("link.jar"), jar.getFileName());
        assertThat(AtomicFile.sameFile(link, jar)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lobby.jar", "LOBBY.JAR", "lobby.JAR"})
    void onACaseInsensitiveVolumeACaseVariantIsTheSameFile(String variant) throws IOException {
        Path jar = Files.writeString(dir.resolve("lobby.jar"), "x");
        assumeTrue(Files.exists(dir.resolve(variant)), "a case-insensitive volume");
        assertThat(AtomicFile.sameFile(dir.resolve(variant), jar)).isTrue();
    }

    /** {@code dir/same}, a link to {@code dir}: paths through it spell the same entries another way. */
    static Path alias(Path dir) throws IOException {
        try {
            return Files.createSymbolicLink(dir.resolve("same"), Path.of("."));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links: " + e.getMessage());
            throw e;
        }
    }

    private void assertNoParts() throws IOException {
        try (Stream<Path> tree = Files.walk(dir)) {
            assertThat(tree.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));
        }
    }
}
