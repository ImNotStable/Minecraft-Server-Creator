package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

import java.lang.reflect.Method;

/**
 * Floating text (a text display entity): lobby leaderboards, labels. Lines join with newlines.
 *
 * <pre>{@code
 * Hologram top = Hologram.spawn(level, new Vec3(0, 65, 0), Component.literal("Top killers"), Component.literal("1. Alex"));
 * }</pre>
 */
public final class Hologram {
    // shortcut: the text display's setters are private in Minecraft; reflection reaches them (names are stable now that
    // Minecraft ships unobfuscated). Switch to an access widener if this library becomes a full mod.
    private static final Method SET_TEXT = method("setText", Component.class);
    private static final Method SET_BILLBOARD = method("setBillboardConstraints", Display.BillboardConstraints.class);
    private static final Method SET_BACKGROUND = method("setBackgroundColor", int.class);

    private final Display.TextDisplay display;

    private Hologram(Display.TextDisplay display) {
        this.display = display;
    }

    private static Method method(String name, Class<?> type) {
        try {
            Method m = name.equals("setBillboardConstraints")
                    ? Display.class.getDeclaredMethod(name, type) : Display.TextDisplay.class.getDeclaredMethod(name, type);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Minecraft changed Display: " + name, e);
        }
    }

    private void call(Method m, Object arg) {
        try {
            m.invoke(display, arg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Spawns turning to face each viewer. It is saved with the world like any entity: remove it when done. */
    public static Hologram spawn(ServerLevel level, Vec3 at, Component... lines) {
        Display.TextDisplay d = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) {
            throw new IllegalStateException("Cannot create a text display");
        }
        d.setPos(at.x(), at.y(), at.z());
        Hologram h = new Hologram(d);
        h.call(SET_BILLBOARD, Display.BillboardConstraints.CENTER);
        h.lines(lines);
        level.addFreshEntity(d);
        return h;
    }

    public Hologram lines(Component... lines) {
        Component text = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            text = text.copy().append(i == 0 ? Component.empty() : Component.literal("\n")).append(lines[i]);
        }
        call(SET_TEXT, text);
        return this;
    }

    /** ARGB background, 0 for none. */
    public Hologram background(int argb) {
        call(SET_BACKGROUND, argb);
        return this;
    }

    public Hologram teleport(Vec3 to) {
        display.teleportTo(to.x(), to.y(), to.z());
        return this;
    }

    public void remove() {
        display.discard();
    }

    public Display.TextDisplay entity() {
        return display;
    }
}
