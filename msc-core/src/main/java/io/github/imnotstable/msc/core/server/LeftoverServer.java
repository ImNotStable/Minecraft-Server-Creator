package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.ServerPropertiesFile;

/** What a start or a stop says about a server an earlier core left running that could not be stopped (#408). */
final class LeftoverServer {
    /** The whole stop, as {@link ServerManager#stopLeftover(boolean)} tries it. */
    static final String TRIED = "asked it to stop over RCON where " + ServerPropertiesFile.NAME + " enables it, ended it, then "
            + "killed it with its child processes, and it is still running";
    /** A start's short try on a leftover that already survived {@link #TRIED}. */
    static final String RESISTED = "it survived a whole stop before (RCON, end, kill), was killed again now, and is "
            + "still running";

    /** A start that found the leftover still running where nothing had tried to stop it. */
    static final String NOT_STOPPED = "it was still running when the server was about to start";

    private LeftoverServer() {
    }

    /** The refusal: the pid, what was tried, that nothing started, and what the user can do. */
    static String stillRunning(long pid, String tried) {
        return "a server an earlier session left running (pid " + pid + ") could not be stopped: " + tried + ". "
                + "Nothing was started or deployed: a new server could not take its world or port, and MSC would lose "
                + "track of this one. Stop process " + pid + " by hand (Windows: Task Manager, or taskkill /F /PID "
                + pid + " from a prompt with the rights it was started with, for example as administrator; "
                + "elsewhere: kill -9 " + pid + "; a process stuck in disk IO ends when the IO does, or at a "
                + "restart), then call again. server_stop tries the whole stop again.";
    }
}
