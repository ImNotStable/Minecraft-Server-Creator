package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Results whose size the server decides are cut at their source, with a marker, so they fit in a frame (#341). */
class ResultLimitsTest {
    @Test
    void cutKeepsShortTextAndCutsLongTextToTheLimitWithAMarker() {
        Random random = new Random(341);
        for (int i = 0; i < 2000; i++) {
            int max = 64 + random.nextInt(2000);
            int length = random.nextInt(3 * max);
            StringBuilder text = new StringBuilder();
            while (text.length() < length) {
                text.appendCodePoint(random.nextInt(4) == 0 ? 0x1F600 + random.nextInt(50) : 'a' + random.nextInt(26));
            }
            String in = text.toString();
            String out = Limits.cut(in, max);
            if (in.length() <= max) {
                assertThat(out).isSameAs(in);
                continue;
            }
            assertThat(out.length()).as("length of %s", out).isLessThanOrEqualTo(max);
            String kept = out.substring(0, out.lastIndexOf(" [cut: "));
            assertThat(in).startsWith(kept);
            assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 1))).isFalse();
            assertThat(out).endsWith(" [cut: " + (in.length() - kept.length()) + " more chars]");
            assertThat(Limits.cut(out, max)).as("cutting again").isSameAs(out);
        }
        assertThat(Limits.cut(null, 64)).isNull();
        assertThatThrownBy(() -> Limits.cut("x", 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityAndSignTextIsCut() {
        String huge = "n".repeat(10 * Limits.MAX_TEXT_CHARS);
        Ops.EntityInfo info = new Ops.EntityInfo("minecraft:text_display", "u", 0, 0, 0, huge, List.of(huge, "short"),
                null, null);
        assertThat(info.name()).isEqualTo(Limits.cut(huge, Limits.MAX_TEXT_CHARS)).endsWith("more chars]");
        assertThat(info.tags().getFirst()).isEqualTo(info.name());
        assertThat(info.tags().get(1)).isEqualTo("short");
        assertThat(new Ops.EntitySummary("minecraft:text_display", 0, 0, 0, huge).name()).isEqualTo(info.name());
        Ops.SignText sign = Ops.SignText.of(0, 0, 0, List.of(huge, "b"), List.of(huge));
        String line = Limits.cut(huge, Limits.MAX_SIGN_LINE_CHARS);
        assertThat(line).hasSizeBetween(Limits.MAX_SIGN_LINE_CHARS - 8, Limits.MAX_SIGN_LINE_CHARS);
        assertThat(sign.front()).containsExactly(line, "b", "", "");
        assertThat(sign.back().getFirst()).isEqualTo(line);
    }

    @Test
    void entitiesStopAtTheTextBudgetAndTotalStillCountsThemAll() {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tags.add(i + "-" + "t".repeat(1000)); // under the per-tag cut, so the 1000 entities share these strings
        }
        List<Ops.EntityInfo> found = new ArrayList<>();
        for (int i = 0; i < Ops.EntitiesResult.MAX_ENTITIES; i++) {
            found.add(new Ops.EntityInfo("minecraft:zombie", "u" + i, i, 64, 0, "z".repeat(5000), tags, 20.0, 20.0));
        }

        Ops.EntitiesResult result = Ops.EntitiesResult.of(found, 0);

        assertThat(result.total()).isEqualTo(found.size());
        assertThat(result.entities()).isNotEmpty().hasSizeLessThan(found.size());
        String frame = MessageCodec.encode(Response.ok(1, Ops.WORLD_ENTITIES.encodeResult(result)));
        assertThat(frame.getBytes(StandardCharsets.UTF_8).length).isLessThan(Limits.MAX_FRAME_BYTES);
        // Small entities are all kept, as before.
        assertThat(Ops.EntitiesResult.of(found.stream().map(e -> new Ops.EntityInfo(e.type(), e.uuid(), e.x(), e.y(),
                e.z(), "z", List.of("t"), null, null)).toList(), 0).entities()).hasSize(found.size());
    }
}
