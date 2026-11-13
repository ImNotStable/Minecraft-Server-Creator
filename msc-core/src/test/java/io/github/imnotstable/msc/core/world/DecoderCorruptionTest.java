package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeded corrupted inputs for every world decoder: each one must return or throw its clean error, never another
 * exception or an OutOfMemoryError, and quickly. A hang fails the method's timeout.
 */
class DecoderCorruptionTest {
    private static final int SEEDS = 150;
    private static final long MAX_MILLIS_EACH = 3_000;
    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);
    private static final String[] TWEAKS = {" (x9999)", " (x0)", "..2147483647 all #", " same as y=-2147483648",
            "y=2147483647", "y=99999999999", " (x1)", "\nlegend: ", "\nlegend: #", "\ny=0..1", "\ny=-1 all"};

    @TempDir
    Path dir;

    private interface Decode {
        void run() throws Exception;
    }

    /** Runs {@code decode}; anything but success or {@code clean} fails with the seed and case. */
    private static void expectClean(String what, long seed, int k, Class<? extends Throwable> clean, Decode decode) {
        long start = System.nanoTime();
        try {
            decode.run();
        } catch (Throwable t) {
            if (!clean.isInstance(t)) {
                fail(what + " seed " + seed + " case " + k + " threw " + t);
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(millis).as("%s seed %d case %d took ms", what, seed, k).isLessThan(MAX_MILLIS_EACH);
    }

    @Test
    @Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
    void corruptedTextGridsGiveToolErrors() {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            BlockGrid g = RandomGrids.grid(rnd, 20, true);
            String text = TextGrid.render(g, rnd.nextBoolean(), Integer.MAX_VALUE);
            for (int k = 0; k < 10; k++) {
                String bad = mutate(rnd, text);
                expectClean("text", seed, k, ToolException.class, () -> TextGrid.parse(bad, "world", ORIGIN));
            }
        }
    }

    @Test
    @Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
    void corruptedSchematicsGiveToolErrors() throws Exception {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            BlockGrid g = RandomGrids.grid(rnd, rnd.nextInt(4) == 0 ? 400 : 20, true);
            Path file = dir.resolve("s.schem");
            Schematic.save(g, file);
            byte[] gz = Files.readAllBytes(file);
            for (int k = 0; k < 12; k++) {
                byte[] bad = k % 2 == 0 ? corruptGzip(rnd, gz) : gzip(corrupt(rnd, gunzip(gz)));
                Path badFile = Files.write(dir.resolve("bad.schem"), bad);
                expectClean("schematic", seed, k, ToolException.class, () -> Schematic.load(badFile, "world", ORIGIN));
            }
        }
    }

    @Test
    @Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
    void corruptedNbtGivesIoErrors() throws Exception {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Path file = dir.resolve("n.schem");
            Schematic.save(RandomGrids.grid(rnd, 40, true), file);
            byte[] raw = gunzip(Files.readAllBytes(file));
            for (int k = 0; k < 12; k++) {
                byte[] bad = gzip(corrupt(rnd, raw));
                expectClean("nbt", seed, k, IOException.class, () -> Nbt.read(new ByteArrayInputStream(bad)));
            }
        }
    }

    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    void corruptedCoordinatesGiveToolErrors() {
        Random rnd = new Random(11);
        String alphabet = "0123456789-, x9";
        for (int k = 0; k < 2_000; k++) {
            StringBuilder text = new StringBuilder();
            for (int i = rnd.nextInt(40); i > 0; i--) {
                text.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            String s = text.toString();
            expectClean("pos", 0, k, ToolException.class, () -> Coords.pos(s));
            expectClean("region", 0, k, ToolException.class, () -> Coords.region("world", s));
            expectClean("area", 0, k, ToolException.class, () -> Coords.area("world", s));
        }
    }

    private static String mutate(Random rnd, String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        switch (rnd.nextInt(4)) {
            case 0:
                return text.substring(0, rnd.nextInt(text.length() + 1));
            case 1: {
                char[] c = text.toCharArray();
                String alphabet = "#.._ab=xy0123456789()-\n:@[] ";
                for (int j = 0; j < 1 + rnd.nextInt(4); j++) {
                    c[rnd.nextInt(c.length)] = alphabet.charAt(rnd.nextInt(alphabet.length()));
                }
                return new String(c);
            }
            case 2:
                Collections.swap(lines, rnd.nextInt(lines.size()), rnd.nextInt(lines.size()));
                return String.join("\n", lines);
            default: {
                int i = rnd.nextInt(lines.size());
                lines.set(i, lines.get(i) + TWEAKS[rnd.nextInt(TWEAKS.length)]);
                return String.join("\n", lines);
            }
        }
    }

    /** Truncate the bytes, overwrite a few of them, or set an int field to an extreme. */
    private static byte[] corrupt(Random rnd, byte[] raw) {
        switch (rnd.nextInt(3)) {
            case 0:
                return Arrays.copyOf(raw, rnd.nextInt(raw.length));
            case 1: {
                byte[] b = raw.clone();
                for (int j = 0; j < 1 + rnd.nextInt(4); j++) {
                    b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
                }
                return b;
            }
            default: {
                byte[] b = raw.clone();
                int at = rnd.nextInt(Math.max(1, b.length - 4));
                int[] extremes = {-1, Integer.MAX_VALUE, Integer.MIN_VALUE, 64 * 1024 * 1024, 0xFFFF};
                int v = extremes[rnd.nextInt(extremes.length)];
                for (int j = 0; j < 4 && at + j < b.length; j++) {
                    b[at + j] = (byte) (v >>> (24 - 8 * j));
                }
                return b;
            }
        }
    }

    private static byte[] corruptGzip(Random rnd, byte[] gz) {
        if (rnd.nextBoolean()) {
            return Arrays.copyOf(gz, rnd.nextInt(gz.length));
        }
        byte[] b = gz.clone();
        for (int j = 0; j < 1 + rnd.nextInt(3); j++) {
            b[rnd.nextInt(b.length)] ^= (byte) (1 + rnd.nextInt(255));
        }
        return b;
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }
}
