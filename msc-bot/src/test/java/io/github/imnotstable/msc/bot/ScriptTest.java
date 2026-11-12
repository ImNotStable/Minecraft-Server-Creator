package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScriptTest {
    @Test
    void parsesActionsWithCommasOrSpaces() {
        assertThat(Script.parse("goto 1.5,-60,2.5 sprint jump")).isInstanceOf(Script.Goto.class);
        assertThat(Script.parse("goto 1.5 -60 2.5")).isInstanceOf(Script.Goto.class);
        assertThat(Script.parse("goto 1.5, 2.5")).isInstanceOf(Script.Goto.class);
        assertThat(Script.parse("forward 20 sprint")).isInstanceOf(Script.Forward.class);
        assertThat(Script.parse("wait 5")).isInstanceOf(Script.Wait.class);
        assertThat(Script.parse("jump")).isInstanceOf(Script.Jump.class);
        for (String ok : new String[] {"tp 0.5 -60 0.5", "look 90 -10", "lookat 1,2,3", "use", "use 1 2 3", "attack",
                "slot 3", "say hi there", "/time set day", "entities", "attack zombie", "interact", "interact villager",
                "inventory", "hud", "close"}) {
            assertThat(Script.parse(ok).text()).isEqualTo(ok);
        }
    }

    @Test
    void badLinesSayWhatIsWrong() {
        assertThatThrownBy(() -> Script.parse("fly 1,2,3")).hasMessageStartingWith("unknown action \"fly\". Actions: goto");
        assertThatThrownBy(() -> Script.parse("tp 1,2")).hasMessage("expected 3 numbers in \"1,2\": tp 1,2");
        assertThatThrownBy(() -> Script.parse("slot 12")).hasMessage("slot is 1 to 9: slot 12");
        assertThatThrownBy(() -> Script.parse("wait")).hasMessage("missing value: wait");
    }

    @Test
    void useSaysWhenTheBlockIsBeyondReach() {
        assertThat(Script.clicked(3.0)).isEqualTo("clicked the block");
        assertThat(Script.clicked(12.34)).isEqualTo("clicked the block from 12.3 blocks: beyond a player's reach (4.5), goto closer");
    }
}
