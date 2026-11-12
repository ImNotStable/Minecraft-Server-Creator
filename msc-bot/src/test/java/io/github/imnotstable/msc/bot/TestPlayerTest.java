package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class TestPlayerTest {
    @Test
    void aPackNeedsPackMetaAtTheZipRoot() throws IOException {
        assertThat(TestPlayer.hasPackMeta(zip("pack.mcmeta"))).isTrue();
        assertThat(TestPlayer.hasPackMeta(zip("mypack/pack.mcmeta"))).isFalse();
        assertThat(TestPlayer.hasPackMeta("not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
    }

    private static byte[] zip(String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write('{');
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
