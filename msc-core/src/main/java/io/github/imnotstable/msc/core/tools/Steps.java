package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.PathArg;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.textures.TextureException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The side effects of one state-changing tool call, in the order they run. The rule every such tool follows:
 * <ol>
 *   <li>check first: every refusal that can be known before a side effect (arguments, that every file the call writes
 *       can be written, that the server can start at all) is decided before the first step, so a refusal is always
 *       the plain one-line answer and nothing was touched;</li>
 *   <li>run the steps in an order where no later step can fail for a reason an earlier check could have caught;</li>
 *   <li>a step that still fails ends the call with a message that names the step, the reason, what was done, what
 *       was not, and what that leaves ({@link #ifFails}), never a bare {@code internal:} error.</li>
 * </ol>
 * A refusal ({@link ToolException}) in the first step, before anything changed, is passed on as it is. An interrupt
 * is passed on too: the session is ending. Tests make any step fail ({@link #failAt}) and read which ran ({@link #trace}).
 */
final class Steps {
    /** A step. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    /** A step that hands a value to the later ones. */
    @FunctionalInterface
    interface Result<T> {
        T get() throws Exception;
    }

    private static final ThreadLocal<String> FAIL_AT = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> TRACE = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<String>> KILL = new ThreadLocal<>();

    private final String tool;
    private final List<String> plan = new ArrayList<>();
    private final Map<String, String> consequences = new HashMap<>();
    private final List<String> done = new ArrayList<>();
    /** Steps that ran and found nothing to do: neither done nor still to do. */
    private final List<String> idle = new ArrayList<>();

    Steps(String tool) {
        this.tool = tool;
    }

    /** Declare the next step, so a failure before it lists it as not done. */
    Steps then(String step) {
        plan.add(step);
        return this;
    }

    /** What a failure of {@code step} leaves behind and how to recover, added to its message. */
    Steps ifFails(String step, String consequence) {
        consequences.put(step, consequence);
        return this;
    }

    /** True when {@code step} was declared. */
    boolean planned(String step) {
        return plan.contains(step);
    }

    void run(String step, Action action) throws InterruptedException {
        run(step, () -> {
            action.run();
            return null;
        });
    }

    <T> T run(String step, Result<T> action) throws InterruptedException {
        return execute(step, action, true);
    }

    /**
     * A step that may find nothing to do: {@code action} returns a note of what it did, or null when it did nothing,
     * and only then is the step counted as done.
     */
    String note(String step, Result<String> action) throws InterruptedException {
        String note = execute(step, action, false);
        (note != null ? done : idle).add(step);
        return note;
    }

    /** Like {@link #note}, for a step that returns whether it did something. */
    boolean ran(String step, Result<Boolean> action) throws InterruptedException {
        boolean ran = execute(step, action, false);
        (ran ? done : idle).add(step);
        return ran;
    }

    private <T> T execute(String step, Result<T> action, boolean countAsDone) throws InterruptedException {
        try {
            begin(step);
            T value = action.get();
            if (countAsDone) {
                markDone(step);
            }
            return value;
        } catch (InterruptedException e) {
            throw e;
        } catch (ToolException | TextureException e) {
            // A refusal: before anything changed, it is the whole answer.
            ToolException refusal = e instanceof ToolException t ? t : new ToolException(e.getMessage());
            throw done.isEmpty() ? refusal : failed(step, refusal);
        } catch (Exception e) {
            throw failed(step, e);
        }
    }

    private void markDone(String step) {
        done.add(step);
    }

    /** Declares {@code step} if it was not, records it for {@link #trace}, and fails it when a test asks. */
    private void begin(String step) throws IOException {
        if (!plan.contains(step)) {
            plan.add(step);
        }
        checkpoint(step);
    }

    /** Records {@code step} for {@link #trace}, and fails it when a test asks. */
    private void checkpoint(String step) throws IOException {
        List<String> trace = TRACE.get();
        if (trace != null) {
            trace.add(tool + ": " + step);
        }
        if (step.equals(FAIL_AT.get())) {
            throw new IOException("injected failure");
        }
    }

    /**
     * An extra, not part of the plan: a step whose failure does not undo the call's purpose (a saved copy, removing
     * MSC's own leftovers). It is never counted as done or not done, so it never changes the shape of a result: a
     * later refusal is still the whole answer, a later failure does not list it. Only its own failure shows, as a
     * note line the caller adds to its answer.
     */
    String optional(String step, Action action) throws InterruptedException {
        try {
            checkpoint(step);
            action.run();
            return "";
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return "\nnot done: " + step + " (" + reason(e) + ")";
        }
    }

    private ToolException failed(String step, Exception e) {
        List<String> notDone = new ArrayList<>();
        for (String s : plan) {
            if (!done.contains(s) && !idle.contains(s)) {
                notDone.add(s);
            }
        }
        StringBuilder text = new StringBuilder(reason(e)).append('\n').append(tool).append(" stopped at: ")
                .append(step).append('\n');
        text.append("done: ").append(done.isEmpty() ? "nothing" : String.join("; ", done)).append('\n');
        text.append("not done: ").append(String.join("; ", notDone));
        String consequence = consequences.get(step);
        if (consequence != null) {
            text.append('\n').append(consequence);
        }
        return new ToolException(text.toString());
    }

    /** One readable line for why a step failed. */
    static String reason(Exception e) {
        return switch (e) {
            case ToolException t -> t.getMessage();
            case TextureException t -> t.getMessage();
            case OpException op -> "agent answered " + op.code() + ": " + op.detail();
            case IOException io -> PathArg.reason(io);
            default -> e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        };
    }

    /**
     * A place between two file operations of a step where the core could be killed (the MCP client closing mid-call,
     * a crash, a power cut). Code that writes, moves or deletes files a server reads calls it between operations;
     * tests ({@link #killPoints}) "kill" the core there and check that what is on disk is harmless and that the next
     * call cleans it up. Outside tests it does nothing.
     */
    static void killPoint(String where) {
        Consumer<String> hook = KILL.get();
        if (hook != null) {
            hook.accept(where);
        }
    }

    /**
     * For tests: run {@code hook} at every {@link #killPoint} reached on this thread; null: none. A hook that kills
     * throws an {@link Error}, which no step catches, after taking what it needs from the disk.
     */
    static void killPoints(Consumer<String> hook) {
        if (hook == null) {
            KILL.remove();
        } else {
            KILL.set(hook);
        }
    }

    /** For tests: make {@code step} of the calls on this thread fail before it runs; null: none. */
    static void failAt(String step) {
        if (step == null) {
            FAIL_AT.remove();
        } else {
            FAIL_AT.set(step);
        }
    }

    /** For tests: record the steps that run on this thread, as {@code tool: step}, until closed. */
    static List<String> trace() {
        List<String> trace = new ArrayList<>();
        TRACE.set(trace);
        return trace;
    }

    /** For tests: stop recording. */
    static void stopTrace() {
        TRACE.remove();
    }
}
