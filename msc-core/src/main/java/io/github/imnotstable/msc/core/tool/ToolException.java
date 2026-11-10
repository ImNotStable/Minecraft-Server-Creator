package io.github.imnotstable.msc.core.tool;

/** A problem the AI can act on: bad arguments, nothing running, a limit exceeded. The message is shown as is. */
public final class ToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
