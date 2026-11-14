package io.github.imnotstable.msc.core.tool;

/** A problem the AI can act on: bad arguments, nothing running, a limit exceeded. The message is shown as is. */
public final class ToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    /** What a workspace part says when a call reaches it after its close (see the ownership model on Core). */
    public static ToolException workspaceClosed(String what) {
        return new ToolException("the workspace was closed (session ended or msc_workspace), so " + what);
    }
}
