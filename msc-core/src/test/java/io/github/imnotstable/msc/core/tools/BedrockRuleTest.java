package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ProxyForwarding;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every platform × forwarding × {@code bedrock} (true, false, not given) × Geyser already in plugins/ (#590): Geyser is
 * installed or kept only on Paper or Purpur without forwarding, {@code bedrock=false} removes it anywhere, and every other
 * combination that would keep or add it is refused.
 */
class BedrockRuleTest {
    private static final Set<Platform> GEYSER_PLATFORMS = Set.of(Platform.PAPER, Platform.PURPUR, Platform.VELOCITY,
            Platform.WATERFALL);

    @TempDir
    Path dir;

    @Test
    void everyCombination() {
        int rows = 0;
        for (Platform platform : Platform.values()) {
            for (boolean forwarding : new boolean[] {false, true}) {
                boolean works = GEYSER_PLATFORMS.contains(platform) && !forwarding;
                for (Boolean bedrock : Arrays.asList(true, false, null)) {
                    for (boolean installed : new boolean[] {false, true}) {
                        rows++;
                        String row = platform.id() + " forwarding=" + forwarding + " bedrock=" + bedrock
                                + " installed=" + installed;
                        Supplier<BedrockRule.Action> decide = () -> BedrockRule.decide(platform, forwarding, bedrock,
                                installed);
                        boolean wantsGeyser = Boolean.TRUE.equals(bedrock) || bedrock == null && installed;
                        if (Boolean.FALSE.equals(bedrock)) {
                            assertThat(decide.get()).as(row).isEqualTo(BedrockRule.Action.REMOVE);
                        } else if (!wantsGeyser) {
                            assertThat(decide.get()).as(row).isEqualTo(BedrockRule.Action.NONE);
                        } else if (works) {
                            assertThat(decide.get()).as(row).isEqualTo(BedrockRule.Action.INSTALL);
                        } else {
                            assertThatThrownBy(decide::get).as(row).isInstanceOf(ToolException.class)
                                    .hasMessageStartingWith(bedrock != null ? "bedrock=true " : "Geyser is in plugins/ ")
                                    .hasMessageContaining(GEYSER_PLATFORMS.contains(platform) ? "behind a proxy"
                                            : "not " + platform.id())
                                    .hasMessageNotContaining("\n");
                        }
                    }
                }
            }
        }
        assertThat(rows).isEqualTo(Platform.values().length * 12);
    }

    @Test
    void theStartLineOnlyWhereGeyserRuns() throws Exception {
        assertThat(BedrockRule.startLine(Platform.PAPER, dir)).isNull();
        Files.createDirectories(BedrockRule.plugins(dir));
        Files.writeString(BedrockRule.plugins(dir).resolve("Geyser-Spigot.jar"), "geyser");
        assertThat(BedrockRule.installed(dir)).isTrue();
        for (Platform platform : Platform.values()) {
            assertThat(BedrockRule.startLine(platform, dir) != null).as(platform.id())
                    .isEqualTo(GEYSER_PLATFORMS.contains(platform));
        }
        ProxyForwarding.backend(dir, "0123456789abcdef0123456789abcdef");
        assertThat(BedrockRule.startLine(Platform.PAPER, dir)).isNull();
        assertThat(BedrockRule.startLine(Platform.PURPUR, dir)).isNull();
    }
}
