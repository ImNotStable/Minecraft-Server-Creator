package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CachedBuildTest {
    @TempDir
    Path cache;

    /** #428: by build number, not by file name, so a build with more digits wins. */
    @ParameterizedTest
    @CsvSource({"9 10, 10", "999 1000, 1000", "10000 9999, 10000", "2 100 99 007, 100", "1, 1"})
    void theNewestIsTheHighestBuildNumber(String builds, int newest) throws IOException {
        for (String build : builds.split(" ")) {
            Files.writeString(cache.resolve("purpur-26.3-" + build + ".jar"), build);
        }
        assertThat(CachedBuild.newest(cache, "purpur", "26.3"))
                .isEqualTo(new CachedBuild(cache.resolve("purpur-26.3-" + newest + ".jar"), newest));
    }

    @Test
    void onlyThatProjectAndVersionCount() throws IOException {
        for (String name : new String[] {"paper-26.3-5000.jar", "purpur-26.3.1-5000.jar", "purpur-26.3-5000.jar.part",
                "purpur-26.3-x.jar", "purpur-26.3-12345678901.jar", "purpur-2603-5000.jar", "purpur-26.3-7.jar"}) {
            Files.writeString(cache.resolve(name), name);
        }
        Files.createDirectories(cache.resolve("purpur-26.3-9000.jar"));
        assertThat(CachedBuild.newest(cache, "purpur", "26.3"))
                .isEqualTo(new CachedBuild(cache.resolve("purpur-26.3-7.jar"), 7));
        assertThat(CachedBuild.newest(cache, "purpur", "1.0")).isNull();
        assertThat(CachedBuild.newest(cache.resolve("missing"), "purpur", "26.3")).isNull();
    }
}
