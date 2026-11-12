package io.github.imnotstable.msc.agent.common;

/**
 * A cursor over the blocks an edit wants to set.
 *
 * @param <S> the platform's block state type
 */
public interface ChangeSource<S> {
    /** Move to the next change; {@code false} when there are no more. */
    boolean advance();

    int x();

    int y();

    int z();

    S target();

    /** Upper bound on the number of changes, used to size buffers. */
    long size();
}
