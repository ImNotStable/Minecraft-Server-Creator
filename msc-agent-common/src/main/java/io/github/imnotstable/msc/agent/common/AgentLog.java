package io.github.imnotstable.msc.agent.common;

/** The few log calls shared agent code makes, so each platform can route them to its own logger. */
public interface AgentLog {
    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message);

    /** Logs to a {@link System.Logger}. */
    static AgentLog of(System.Logger logger) {
        return new AgentLog() {
            @Override
            public void debug(String message) {
                logger.log(System.Logger.Level.DEBUG, message);
            }

            @Override
            public void info(String message) {
                logger.log(System.Logger.Level.INFO, message);
            }

            @Override
            public void warn(String message) {
                logger.log(System.Logger.Level.WARNING, message);
            }

            @Override
            public void error(String message) {
                logger.log(System.Logger.Level.ERROR, message);
            }
        };
    }
}
