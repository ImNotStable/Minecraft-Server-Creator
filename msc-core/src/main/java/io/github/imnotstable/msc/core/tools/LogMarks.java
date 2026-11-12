package io.github.imnotstable.msc.core.tools;

/** Where the log readers start: the last server start, and where {@code server_wait} looks from. */
final class LogMarks {
    /** Log position of the last start. */
    volatile long start;
    /** Where server_wait starts looking: after the last start, server_command or server_wait match. */
    volatile long waitFrom;

    void started(long mark) {
        start = mark;
        waitFrom = mark;
    }
}
