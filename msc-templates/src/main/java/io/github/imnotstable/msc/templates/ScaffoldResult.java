package io.github.imnotstable.msc.templates;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What {@link Templates#scaffold} wrote.
 *
 * @param root   the target directory
 * @param files  every file written, as absolute paths, in template order
 * @param values every value the template was rendered with (variables and pinned versions), for
 *               example {@code rconPassword}, {@code rconPort}, {@code serverPort}, {@code mcVersion}
 */
public record ScaffoldResult(Path root, List<Path> files, Map<String, String> values) {
    public ScaffoldResult {
        files = List.copyOf(files);
        values = Map.copyOf(values);
    }
}
