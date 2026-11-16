package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFileTest {
    @Test
    void onlyTheOwnerCanReadIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mcp.json");
        Files.writeString(file, "old");
        PrivateFile.write(file, "{\"MSC_TOKEN\":\"t\"}", Disk.REAL);
        assertThat(Files.readString(file)).isEqualTo("{\"MSC_TOKEN\":\"t\"}");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
    }
}
