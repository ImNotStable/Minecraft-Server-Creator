package io.github.imnotstable.msc.bench.task;

import java.util.Objects;

/**
 * A project template from msc-templates written into the workspace before the fixtures, the same way msc-core's
 * {@code scaffold} tool would, so both modes start from the identical project.
 *
 * @param template    template id, such as {@code minestom-game}
 * @param name        project name, such as {@code Sprint} (gives class names and the jar name)
 * @param packageName Java package of the project's classes
 */
public record Scaffold(String template, String name, String packageName) {

    public Scaffold {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(packageName, "package");
    }
}
