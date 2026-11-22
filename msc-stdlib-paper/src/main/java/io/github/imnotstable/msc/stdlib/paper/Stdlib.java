package io.github.imnotstable.msc.stdlib.paper;

import org.bukkit.plugin.Plugin;

/**
 * Call {@link #init} first thing in {@code onEnable}: it registers the listeners {@link Gui} and
 * {@link PlayerData} need and the scheduler they use.
 */
public final class Stdlib {
    private static Plugin plugin;
    private static PaperScheduler scheduler;

    private Stdlib() {}

    public static void init(Plugin owner) {
        plugin = owner;
        scheduler = new PaperScheduler(owner);
        owner.getServer().getPluginManager().registerEvents(new Gui.Listener(), owner);
        Prompt.reset();
        CustomItems.reset();
        Regions.reset();
    }

    public static Plugin plugin() {
        if (plugin == null) {
            throw new IllegalStateException("Call Stdlib.init(this) in onEnable first");
        }
        return plugin;
    }

    /** The plugin's scheduler. */
    public static PaperScheduler scheduler() {
        plugin();
        return scheduler;
    }
}
