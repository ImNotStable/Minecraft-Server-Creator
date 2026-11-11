package io.github.imnotstable.msc.bench.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Passes when a workspace PNG meets its {@link PngRules}. */
record PngCheck(String name, String path, PngRules rules) implements Check {

    @Override
    public CheckResult run(CheckContext context) {
        Path file = context.workspace().resolve(path);
        if (!Files.isRegularFile(file)) {
            return CheckResult.fail(name, path + " not found");
        }
        try {
            List<String> problems = rules.problems(Files.readAllBytes(file));
            return problems.isEmpty()
                    ? CheckResult.pass(name, path + " meets the rules")
                    : CheckResult.fail(name, path + ": " + String.join("; ", problems));
        } catch (IOException e) {
            return CheckResult.fail(name, "cannot read " + path + ": " + e.getMessage());
        }
    }
}
