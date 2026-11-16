package io.github.imnotstable.msc.core.fixture;

import java.net.BindException;

/**
 * A stand-in for the standalone pack host that cannot take its port ({@code <host> <port> <dir>}): it dies the way a
 * JVM does on an uncaught exception with a cause, so its log ends in a trace line such as {@code ... 1 more} (#744).
 */
public final class FailingPackHostMain {
    private FailingPackHostMain() {
    }

    public static void main(String[] args) throws BindException {
        BindException taken = new BindException("port " + args[1] + " is taken: Address already in use");
        taken.initCause(new BindException("Address already in use"));
        throw taken;
    }
}
