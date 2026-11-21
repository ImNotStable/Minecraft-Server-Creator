package io.github.imnotstable.msc.bench.run;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** For tests outside this package: other processes' arguments, also on Windows where Java shows none. */
public final class ProcessArgumentsAccess {
    private ProcessArgumentsAccess() {}

    public static Function<ProcessHandle, Optional<List<String>>> snapshot() {
        return ProcessArguments.snapshot();
    }
}
