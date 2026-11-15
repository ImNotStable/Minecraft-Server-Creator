package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * A step of the bench's own work on input it already accepted failed, such as freeing the bench's ports or the reset
 * before the checks (#639). That is a failed attempt or check, never a bad argument: a run records it as the attempt's
 * error, and {@code check} exits with the code of a failed check. The message names the step:
 * {@code <step>: <what went wrong>}, as a run records it.
 */
public final class StepFailed extends IOException {

    private static final long serialVersionUID = 1L;

    /** Freeing the bench's game and RCON ports after the agent, before the checks. */
    public static final String FREEING_PORTS_BEFORE_CHECKS = "freeing the bench's ports before the checks";
    /** Task.json's {@code resetBeforeChecks}. */
    public static final String RESET_BEFORE_CHECKS = "reset before checks";

    /** One step's work. */
    @FunctionalInterface
    public interface Work {
        void run() throws IOException;
    }

    private final String step;

    public StepFailed(String step, Exception cause) {
        super(step + ": " + describe(cause), cause);
        this.step = step;
    }

    /** The step that failed, such as {@link #RESET_BEFORE_CHECKS}. */
    public String step() {
        return step;
    }

    /** Runs {@code work}, turning an IO failure in it into this step's failure; a step's failure passes unchanged. */
    public static void during(String step, Work work) throws IOException {
        try {
            work.run();
        } catch (StepFailed e) {
            throw e;
        } catch (IOException e) {
            throw new StepFailed(step, e);
        } catch (UncheckedIOException e) {
            throw new StepFailed(step, e.getCause());
        }
    }

    /** The exception's message, with the path and what went wrong for a file-system error, whose message is bare. */
    public static String describe(Exception e) {
        if (e instanceof NoSuchFileException missing) {
            return missing.getFile() + " does not exist";
        }
        if (e instanceof AccessDeniedException denied) {
            return denied.getFile() + ": access denied";
        }
        if (e instanceof FileSystemException fs && fs.getReason() == null) {
            return fs.getFile() + ": " + fs.getClass().getSimpleName();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
