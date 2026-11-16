package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The machine's store of server jars that came with a checksum (Paper's sha256, Purpur's md5), named by that checksum
 * and shared by every workspace on the machine, so a new workspace, a bench run or a second project copies the jar
 * instead of downloading it again (ADR 0038). A jar is used only when its bytes still match the name, so a damaged or
 * planted file is downloaded over, never served. Keeping a jar is best effort: a store that cannot be written costs a
 * download next time, never a failed {@code server_setup}.
 *
 * @param dir the store's folder, or null for no store (tests, {@code MSC_DOWNLOAD_STORE=none})
 */
public record DownloadStore(Path dir) {
    /** The environment variable that moves the store, or turns it off with {@code none}. */
    public static final String ENV = "MSC_DOWNLOAD_STORE";
    /** No store: every download goes to the network. */
    public static final DownloadStore NONE = new DownloadStore(null);

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{8,128}");

    /** {@code MSC_DOWNLOAD_STORE} if set ({@code none} turns the store off), else {@code ~/.msc/downloads}. */
    public static DownloadStore fromEnvironment(Map<String, String> env, Path home) {
        String set = env.get(ENV);
        if (set == null || set.isBlank()) {
            return new DownloadStore(home.resolve(".msc").resolve("downloads"));
        }
        return set.strip().equalsIgnoreCase("none") ? NONE : new DownloadStore(Path.of(set.strip()));
    }

    /** The stored file that matches {@code checksum}, or null when there is none or it no longer matches. */
    Path find(Downloads.Checksum checksum) throws IOException {
        Path file = file(checksum);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return checksum.hex().equalsIgnoreCase(Downloads.digest(file, checksum.algorithm())) ? file : null;
    }

    /** Keeps a copy of {@code downloaded}, whose bytes matched {@code checksum}; false when it could not. */
    boolean keep(Path downloaded, Downloads.Checksum checksum) {
        Path file = file(checksum);
        if (file == null) {
            return false;
        }
        try {
            AtomicFile.copy(downloaded, file);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** {@code <dir>/<algorithm>-<hex>.jar}, or null without a store or for a checksum unfit for a file name. */
    private Path file(Downloads.Checksum checksum) {
        if (dir == null || checksum == null) {
            return null;
        }
        String hex = checksum.hex().toLowerCase(Locale.ROOT);
        String algorithm = checksum.algorithm().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return HEX.matcher(hex).matches() && !algorithm.isEmpty() ? dir.resolve(algorithm + "-" + hex + ".jar") : null;
    }
}
