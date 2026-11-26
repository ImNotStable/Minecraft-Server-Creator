package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** ADR 0119: an identical call that gets an identical answer within the window is answered in one line. */
class RepeatsTest {
    private static final String LONG = "row of blocks\n".repeat(30);

    @Test
    void anIdenticalAnswerToAnIdenticalCallIsOneLine() {
        ToolRegistry registry = new ToolRegistry(new ToolLog(null));
        AtomicReference<String> answer = new AtomicReference<>(LONG);
        registry.add("world_slice", "Look.", Schema.of().opt("at", "string", "Where"), args -> answer.get());
        JsonObject here = new JsonObject();
        here.addProperty("at", "here");
        assertThat(registry.call("world_slice", here).text()).isEqualTo(LONG);
        assertThat(registry.call("world_slice", here).text()).startsWith("same result as your identical world_slice call 1 call ago");
        // Other arguments, or a changed answer, come in full.
        JsonObject there = new JsonObject();
        there.addProperty("at", "there");
        assertThat(registry.call("world_slice", there).text()).isEqualTo(LONG);
        answer.set(LONG + "a new block");
        assertThat(registry.call("world_slice", here).text()).endsWith("a new block");
    }

    @Test
    void shortAnswersErrorsAndOldAnswersAreKept() {
        Repeats repeats = new Repeats();
        assertThat(repeats.check("world_entities", "{}", "pong", null)).isNull();
        assertThat(repeats.check("world_entities", "{}", "pong", null)).isNull();
        assertThat(repeats.check("world_slice", "{}", LONG, null)).isNull();
        for (int i = 0; i < Repeats.WINDOW; i++) {
            repeats.check("other", "{}", null, null); // errors count as calls but are never shortened
        }
        assertThat(repeats.check("world_slice", "{}", LONG, null)).isNull(); // out of the window: it may be out of context too
        assertThat(repeats.check("world_slice", "{}", LONG, null)).contains("1 call ago");
    }

    @Test
    void aToolThatActsKeepsItsAnswer() {
        Repeats repeats = new Repeats();
        assertThat(repeats.check("dev_cycle", "{}", LONG, null)).isNull();
        assertThat(repeats.check("dev_cycle", "{}", LONG, null)).isNull(); // it redeployed again, whatever it says
    }

    @Test
    void anImageCountsAsPartOfTheAnswer() {
        Repeats repeats = new Repeats();
        byte[] png = new byte[500];
        assertThat(repeats.check("world_render", "{}", "caption", png)).isNull();
        png[3] = 1;
        assertThat(repeats.check("world_render", "{}", "caption", png)).isNull();
        assertThat(repeats.check("world_render", "{}", "caption", png)).startsWith("same image and text");
    }
}
