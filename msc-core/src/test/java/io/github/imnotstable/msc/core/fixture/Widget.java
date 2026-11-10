package io.github.imnotstable.msc.core.fixture;

/** An interface with a default method, indexed by {@code ApiIndexTest}. */
public interface Widget {
    String label();

    default boolean isLabelled() {
        return label() != null;
    }
}
