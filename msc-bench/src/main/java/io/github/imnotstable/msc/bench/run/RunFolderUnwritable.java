package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;

/**
 * One of the bench's own output files could not be written, such as on a full disk. In a run: the run folder itself,
 * an attempt's folder, {@code mcp.json}, {@code prompt.md} or agent output file, or {@code results.jsonl}
 * ({@link BenchRunner}'s one rule for an IO failure, #615); every later attempt would fail the same way, so the run
 * stops there with the attempts {@code results.jsonl} holds. Outside a run: the {@code report.md} that {@code report}
 * writes (#644). The command line gives it an exit code of its own, never the one for a bad argument. The message
 * names the file and the cause: {@code <file> could not be written (<cause>)}.
 */
public final class RunFolderUnwritable extends IOException {

    private static final long serialVersionUID = 1L;

    public RunFolderUnwritable(Path file, IOException cause) {
        super(file + " could not be written (" + reason(file, cause) + ")", cause);
    }

    /** What went wrong, without the file again: a file-system error's message repeats its file. */
    private static String reason(Path file, IOException e) {
        if (e instanceof FileSystemException fs) {
            String what = fs.getReason() != null ? fs.getReason()
                    : fs instanceof FileAlreadyExistsException ? "a file is in the way"
                    : fs.getClass().getSimpleName();
            return fs.getFile() == null || fs.getFile().equals(file.toString()) ? what : fs.getFile() + ": " + what;
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
