package io.github.imnotstable.msc.bot;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

/**
 * The few MCProtocolLib calls that differ between the protocol versions the bot runs against. The same compiled bot
 * runs with MCProtocolLib 26.3 and, in its own class loader, 26.2 (see {@link Bots}), so these go through reflection.
 */
final class Compat {
    private static final Method SYNC_POSITION = method(ClientboundEntityPositionSyncPacket.class, "getEndPosition",
            "getPosition");
    private static final Constructor<?> ACCEPT_FULL = constructor(ServerboundAcceptTeleportationPacket.class,
            int.class, double.class, double.class, double.class, float.class, float.class);
    private static final Constructor<?> ACCEPT_ID = constructor(ServerboundAcceptTeleportationPacket.class, int.class);

    private Compat() {
    }

    /** Where an entity position sync puts the entity, or null when it carries no end position. */
    static Vector3d syncPosition(ClientboundEntityPositionSyncPacket packet) {
        return (Vector3d) invoke(() -> SYNC_POSITION.invoke(packet));
    }

    /** The teleport confirmation; 26.3 also echoes the position the client ended up at. */
    static Packet acceptTeleport(int id, double x, double y, double z, float yaw, float pitch) {
        return (Packet) invoke(() -> ACCEPT_FULL != null ? ACCEPT_FULL.newInstance(id, x, y, z, yaw, pitch)
                : ACCEPT_ID.newInstance(id));
    }

    private interface Call {
        Object call() throws ReflectiveOperationException;
    }

    private static Object invoke(Call call) {
        try {
            return call.call();
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException e) {
                // try the next name
            }
        }
        throw new IllegalStateException(type.getSimpleName() + " has none of " + String.join(", ", names));
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) {
        try {
            return type.getConstructor(parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
