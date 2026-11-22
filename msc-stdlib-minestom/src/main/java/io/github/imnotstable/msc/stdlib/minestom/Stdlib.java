package io.github.imnotstable.msc.stdlib.minestom;

import net.minestom.server.ServerProcess;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.Event;

/**
 * Call {@link #init} once, after {@code MinecraftServer.init()}: {@code Stdlib.init(MinecraftServer.process())}.
 * It registers what {@link Gui} and {@link PlayerData} listen to, on their own event node.
 */
public final class Stdlib {
    private static ServerProcess process;
    private static EventNode<Event> node;
    private static MinestomScheduler scheduler;

    private Stdlib() {}

    public static void init(ServerProcess server) {
        if (node != null && process != null) {
            process.eventHandler().removeChild(node);
        }
        process = server;
        scheduler = new MinestomScheduler(server.scheduler());
        node = EventNode.all("msc-stdlib");
        server.eventHandler().addChild(node);
        Gui.listen(node);
        CustomItems.reset();
        Regions.reset();
        Prompt.reset();
    }

    /** The node the library's listeners hang on; add yours to your own nodes. */
    static EventNode<Event> events() {
        if (node == null) {
            throw new IllegalStateException("Call Stdlib.init(MinecraftServer.process()) first");
        }
        return node;
    }

    public static MinestomScheduler scheduler() {
        events();
        return scheduler;
    }
}
