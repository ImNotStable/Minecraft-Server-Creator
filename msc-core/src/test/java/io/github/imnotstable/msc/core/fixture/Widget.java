package io.github.imnotstable.msc.core.fixture;

/** An interface with a default method, indexed by {@code ApiIndexTest}. */
public interface Widget {
    int MAX_WIDTH = 32;

    String label();

    default boolean isLabelled() {
        return label() != null;
    }
}
