package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.file.Path;

/** A second bench process for ResultsFileTest: appends {@code count} records named {@code <prefix>-<i>} to a file. */
public final class AppendRecords {

    private AppendRecords() {
    }

    public static void main(String[] args) throws IOException {
        Path file = Path.of(args[0]);
        for (int i = 0; i < Integer.parseInt(args[2]); i++) {
            ResultsFile.append(file, ResultsFileTest.record(args[1] + "-" + i));
        }
    }
}
