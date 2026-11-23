package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #592: a stack frame of the server itself is framework on every platform, so a trace never blames the server's code
 * as the project's, and the server's frames fold. Each platform names its packages ({@link Platform#packages}), and
 * {@link LogCondenser} takes them all.
 */
class FrameworkPackagesTest {
    @TempDir
    Path dir;

    /** A class from each platform's own server, as its stack traces show it; every platform must have one here. */
    private static final Map<Platform, List<String>> SERVER_CLASSES = Map.of(
            Platform.PAPER, List.of("io.papermc.paper.plugin.manager.PaperPluginInstanceManager",
                    "org.bukkit.craftbukkit.CraftServer", "net.minecraft.server.MinecraftServer",
                    "net.kyori.adventure.text.Component", "net.md_5.bungee.api.chat.TextComponent"),
            Platform.PURPUR, List.of("org.purpurmc.purpur.PurpurConfig", "org.bukkit.plugin.SimplePluginManager"),
            Platform.FOLIA, List.of("io.papermc.paper.threadedregions.RegionizedServer",
                    "ca.spottedleaf.moonrise.common.util.TickThread"),
            Platform.FABRIC, List.of("net.fabricmc.loader.impl.launch.knot.Knot",
                    "org.spongepowered.asm.mixin.transformer.MixinProcessor"),
            Platform.QUILT, List.of("org.quiltmc.loader.impl.launch.knot.Knot", "net.fabricmc.api.ModInitializer"),
            Platform.NEOFORGE, List.of("net.neoforged.fml.javafmlmod.FMLModContainer", "net.minecraft.server.Main"),
            Platform.FORGE, List.of("net.minecraftforge.eventbus.EventBus", "cpw.mods.modlauncher.Launcher"),
            Platform.MINESTOM, List.of("net.minestom.server.MinecraftServer"),
            Platform.VELOCITY, List.of("com.velocitypowered.proxy.connection.MinecraftConnection",
                    "com.velocitypowered.proxy.event.VelocityEventManager"))
            .entrySet().stream().collect(java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue),
                    m -> {
                        m.put(Platform.WATERFALL, List.of("net.md_5.bungee.BungeeCord",
                                "io.github.waterfallmc.waterfall.conf.WaterfallConfiguration"));
                        return Map.copyOf(m);
                    }));

    @Test
    void everyPlatformsServerCodeIsFramework() {
        assertThat(SERVER_CLASSES).as("a server class for every platform").containsOnlyKeys(Platform.values());
        SERVER_CLASSES.forEach((platform, classes) -> {
            assertThat(platform.packages()).as(platform.id()).isNotEmpty();
            for (String c : classes) {
                assertThat(LogCondenser.framework(c + ".run")).as(platform.id() + ": " + c).isTrue();
            }
        });
        // Geyser and Floodgate are plugins server_setup installs, not the project's code.
        assertThat(LogCondenser.framework("org.geysermc.geyser.GeyserImpl.start")).isTrue();
        assertThat(LogCondenser.framework("com.example.myplugin.MyPlugin.onEnable")).isFalse();
        assertThat(Arrays.stream(Platform.values()).flatMap(p -> p.packages().stream()))
                .allMatch(p -> p.endsWith(".") && LogCondenser.framework(p + "X.run"));
    }

    @Test
    void anErrorInsideVelocityIsTheServersAndItsFramesFold() {
        List<String> inProxy = List.of(
                "[12:00:02 ERROR]: Exception in proxy",
                "java.lang.NullPointerException: x",
                "\tat com.velocitypowered.proxy.connection.MinecraftConnection.channelRead(MinecraftConnection.java:150) ~[velocity.jar:3.4.0]",
                "\tat com.velocitypowered.proxy.connection.MinecraftConnection.foo(MinecraftConnection.java:151) ~[velocity.jar:3.4.0]",
                "\tat io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:1) ~[velocity.jar:3.4.0]");

        assertThat(CrashTriage.triage(inProxy, dir, null, Platform.VELOCITY).render(null))
                .contains("in server code").doesNotContain("fix the code at MinecraftConnection.java");

        List<String> plugin = List.of(
                "[12:00:03 ERROR]: Couldn't pass PostLoginEvent to myplugin",
                "java.lang.IllegalStateException: boom",
                "\tat com.example.myplugin.Join.onLogin(Join.java:9) ~[myplugin.jar:?]",
                "\tat com.velocitypowered.proxy.event.UntargetedEventHandler.execute(UntargetedEventHandler.java:1) ~[velocity.jar:3.4.0]",
                "\tat com.velocitypowered.proxy.event.VelocityEventManager.fire(VelocityEventManager.java:2) ~[velocity.jar:3.4.0]",
                "\tat java.base/java.lang.Thread.run(Thread.java:1583) ~[?:?]");

        assertThat(LogCondenser.trimTrace(plugin.subList(1, plugin.size()))).containsExactly(
                "java.lang.IllegalStateException: boom",
                "\tat com.example.myplugin.Join.onLogin(Join.java:9) ~[myplugin.jar:?]",
                "\t... 3 framework frames");
    }
}
