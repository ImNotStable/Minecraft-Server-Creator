package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Call {@link #init} in your {@code ProxyInitializeEvent} handler: {@code Stdlib.init(this, server)}. It holds the
 * proxy and the scheduler the other classes use.
 */
public final class Stdlib {
    private static Object plugin;
    private static ProxyServer proxy;
    private static VelocityScheduler scheduler;

    private Stdlib() {}

    public static void init(Object owner, ProxyServer server) {
        plugin = owner;
        proxy = server;
        scheduler = new VelocityScheduler(owner, server);
    }

    public static ProxyServer proxy() {
        check();
        return proxy;
    }

    static Object plugin() {
        check();
        return plugin;
    }

    /** The plugin's scheduler: ticks are 50 ms, as on a game server; "sync" runs on Velocity's task pool. */
    public static VelocityScheduler scheduler() {
        check();
        return scheduler;
    }

    private static void check() {
        if (proxy == null) {
            throw new IllegalStateException("Call Stdlib.init(this, server) in your ProxyInitializeEvent handler first");
        }
    }
}
