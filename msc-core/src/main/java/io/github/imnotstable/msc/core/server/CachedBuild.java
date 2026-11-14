package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A server jar in {@code .msc/cache} named {@code <project>-<version>-<build>.jar}: the offline fallback of the
 * downloaders that pick a build from a build API (Paper, Purpur). The one lookup of the newest such build, by build
 * number, so build 1000 beats build 999 (#428).
 */
record CachedBuild(Path jar, int build) {

    /** The cached jar of {@code project} and {@code version} with the highest build number, or null. */
    static CachedBuild newest(Path cacheDir, String project, String version) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return null;
        }
        Pattern name = Pattern.compile(Pattern.quote(project + "-" + version + "-") + "(\\d{1,9})\\.jar");
        try (Stream<Path> files = Files.list(cacheDir)) {
            return files.map(p -> {
                Matcher m = name.matcher(p.getFileName().toString());
                return m.matches() && Files.isRegularFile(p) ? new CachedBuild(p, Integer.parseInt(m.group(1))) : null;
            }).filter(c -> c != null).max(Comparator.comparingInt(CachedBuild::build)).orElse(null);
        }
    }
}
