package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.server.BedrockConfig;
import io.github.imnotstable.msc.core.server.GeyserDownloader;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ProxyForwarding;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where Geyser runs and what {@code server_setup} does with it, in one table for the setup and the start report
 * (ADR 0046, #590). Geyser-Spigot loads as a Paper or Purpur plugin and fronts that server's own Java port, so it works
 * there and only while Velocity forwarding is off: a backend with modern forwarding refuses direct joins, Geyser's
 * Bedrock players included (ADR 0045). The jars live in {@code plugins/} whatever the platform, so removal works on any.
 */
final class BedrockRule {
    /** What the setup does with Geyser and Floodgate. */
    enum Action { NONE, INSTALL, REMOVE }

    private BedrockRule() {
    }

    /** Where Geyser and Floodgate are, on every platform: a folder switched away from Paper keeps its plugins/. */
    static Path plugins(Path serverDir) {
        return serverDir.resolve(Platform.PAPER.addonDir());
    }

    /** True when Geyser-Spigot is in {@code plugins/}. */
    static boolean installed(Path serverDir) {
        return Files.isRegularFile(plugins(serverDir).resolve(GeyserDownloader.Project.GEYSER.jarName()));
    }

    /** Why Geyser cannot work on {@code platform} with {@code forwarding}, or null where it works. */
    static String blocker(Platform platform, boolean forwarding) {
        if (platform != Platform.PAPER && platform != Platform.PURPUR) {
            return "needs paper or purpur (Geyser and Floodgate install as plugins), not " + platform.id();
        }
        return forwarding ? "does not work with proxy=velocity (the backend refuses direct joins, Geyser's too)" : null;
    }

    /**
     * The setup's action for the final {@code platform} and {@code forwarding}, given {@code bedrock} (null when not
     * given) and whether Geyser is {@code installed}. A combination that cannot work is refused here, before anything
     * changes: {@code bedrock=true}, or Geyser kept by a plain setup, where Geyser cannot run.
     */
    static Action decide(Platform platform, boolean forwarding, Boolean bedrock, boolean installed) {
        if (Boolean.FALSE.equals(bedrock)) {
            return Action.REMOVE;
        }
        String blocker = blocker(platform, forwarding);
        if (Boolean.TRUE.equals(bedrock)) {
            if (blocker != null) {
                throw new ToolException("bedrock=true " + blocker);
            }
            return Action.INSTALL;
        }
        if (!installed) {
            return Action.NONE;
        }
        if (blocker != null) {
            throw new ToolException("Geyser is in plugins/ and " + blocker + ": bedrock=false removes it");
        }
        return Action.INSTALL;
    }

    /** The start report's {@code bedrock:} line, or null where Geyser is not installed or cannot work. */
    static String startLine(Platform platform, Path serverDir) {
        try {
            return blocker(platform, ProxyForwarding.enabled(serverDir)) == null ? BedrockConfig.line(serverDir) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
