package io.github.imnotstable.msc.stdlib.bungeecord;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * Call {@link #init} in your plugin's {@code onEnable}: {@code Stdlib.init(this)}. It holds the plugin, the proxy and the
 * scheduler the other classes use. Works on BungeeCord and Waterfall.
 */
public final class Stdlib {
    private static Plugin plugin;
    private static ProxyServer proxy;
    private static BungeeScheduler scheduler;

    private Stdlib() {}

    public static void init(Plugin owner) {
        init(owner, owner.getProxy());
    }

    /** As {@link #init(Plugin)}, with the proxy given (tests pass their own). */
    public static void init(Plugin owner, ProxyServer server) {
        plugin = owner;
        proxy = server;
        scheduler = new BungeeScheduler(owner, server.getScheduler());
    }

    public static ProxyServer proxy() {
        check();
        return proxy;
    }

    static Plugin plugin() {
        check();
        return plugin;
    }

    /** The plugin's scheduler: ticks are 50 ms, as on a game server; "sync" runs on BungeeCord's task pool. */
    public static BungeeScheduler scheduler() {
        check();
        return scheduler;
    }

    private static void check() {
        if (proxy == null) {
            throw new IllegalStateException("Call Stdlib.init(this) in your plugin's onEnable first");
        }
    }
}
