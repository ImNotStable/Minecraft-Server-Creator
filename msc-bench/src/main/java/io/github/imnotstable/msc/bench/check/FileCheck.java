package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Passes when a workspace file exists and, if a pattern is given, its text contains a match. The agent wrote the file,
 * so it is read leniently ({@link Text#read}): a byte-order mark or a stray non-UTF-8 byte does not fail the check.
 */
record FileCheck(String name, String path, Pattern contains) implements Check {

    @Override
    public CheckResult run(CheckContext context) {
        Path file = context.workspace().resolve(path);
        if (!Files.isRegularFile(file)) {
            return CheckResult.fail(name, path + " not found");
        }
        if (contains == null) {
            return CheckResult.pass(name, path + " exists");
        }
        try {
            String text = Text.read(file);
            return contains.matcher(text).find()
                    ? CheckResult.pass(name, path + " matches /" + contains.pattern() + "/")
                    : CheckResult.fail(name, path + " has no match for /" + contains.pattern() + "/");
        } catch (IOException e) {
            return CheckResult.fail(name, "cannot read " + path + ": " + e.getMessage());
        }
    }
}
