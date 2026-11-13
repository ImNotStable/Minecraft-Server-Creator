package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The test player's life-cycle table, every (state, input) pair, and the waits that drive its timed rows. */
class LifeTest {
    static final String EXPECTED = """
            | state | input | next state | actions |
            |---|---|---|---|
            | SPAWNING | LOGIN | SPAWNING | - |
            | SPAWNING | RESPAWN | SPAWNING | - |
            | SPAWNING | POSITION | ALIVE | LOADED |
            | SPAWNING | DIED | SPAWNING_DEAD | END_STEP |
            | SPAWNING | WIN_GAME | SPAWNING | ASK_RESPAWN |
            | SPAWNING | CONFIGURE | CONFIGURING | END_STEP |
            | SPAWNING | DISCONNECT | DISCONNECTED | END_STEP |
            | SPAWNING | TICK | SPAWNING | - |
            | SPAWNING | WAIT_OVER | SPAWNING | - |
            | SPAWNING_DEAD | LOGIN | SPAWNING | - |
            | SPAWNING_DEAD | RESPAWN | SPAWNING | - |
            | SPAWNING_DEAD | POSITION | DEAD | LOADED |
            | SPAWNING_DEAD | DIED | SPAWNING_DEAD | - |
            | SPAWNING_DEAD | WIN_GAME | SPAWNING_DEAD | ASK_RESPAWN |
            | SPAWNING_DEAD | CONFIGURE | CONFIGURING | END_STEP |
            | SPAWNING_DEAD | DISCONNECT | DISCONNECTED | END_STEP |
            | SPAWNING_DEAD | TICK | SPAWNING_DEAD | - |
            | SPAWNING_DEAD | WAIT_OVER | SPAWNING_DEAD | - |
            | ALIVE | LOGIN | SPAWNING | - |
            | ALIVE | RESPAWN | SPAWNING | - |
            | ALIVE | POSITION | ALIVE | - |
            | ALIVE | DIED | DEAD | END_STEP |
            | ALIVE | WIN_GAME | CREDITS | ASK_RESPAWN, END_STEP |
            | ALIVE | CONFIGURE | CONFIGURING | END_STEP |
            | ALIVE | DISCONNECT | DISCONNECTED | END_STEP |
            | ALIVE | TICK | ALIVE | MOVE |
            | ALIVE | WAIT_OVER | ALIVE | - |
            | DEAD | LOGIN | SPAWNING | - |
            | DEAD | RESPAWN | SPAWNING | - |
            | DEAD | POSITION | DEAD | - |
            | DEAD | DIED | DEAD | - |
            | DEAD | WIN_GAME | RESPAWN_ASKED | ASK_RESPAWN |
            | DEAD | CONFIGURE | CONFIGURING | END_STEP |
            | DEAD | DISCONNECT | DISCONNECTED | END_STEP |
            | DEAD | TICK | DEAD | - |
            | DEAD | WAIT_OVER | RESPAWN_ASKED | ASK_RESPAWN |
            | RESPAWN_ASKED | LOGIN | SPAWNING | - |
            | RESPAWN_ASKED | RESPAWN | SPAWNING | - |
            | RESPAWN_ASKED | POSITION | RESPAWN_ASKED | - |
            | RESPAWN_ASKED | DIED | RESPAWN_ASKED | - |
            | RESPAWN_ASKED | WIN_GAME | RESPAWN_ASKED | ASK_RESPAWN |
            | RESPAWN_ASKED | CONFIGURE | CONFIGURING | END_STEP |
            | RESPAWN_ASKED | DISCONNECT | DISCONNECTED | END_STEP |
            | RESPAWN_ASKED | TICK | RESPAWN_ASKED | - |
            | RESPAWN_ASKED | WAIT_OVER | RESPAWN_ASKED | - |
            | CREDITS | LOGIN | SPAWNING | - |
            | CREDITS | RESPAWN | SPAWNING | - |
            | CREDITS | POSITION | ALIVE | - |
            | CREDITS | DIED | DEAD | END_STEP |
            | CREDITS | WIN_GAME | CREDITS | ASK_RESPAWN |
            | CREDITS | CONFIGURE | CONFIGURING | END_STEP |
            | CREDITS | DISCONNECT | DISCONNECTED | END_STEP |
            | CREDITS | TICK | CREDITS | - |
            | CREDITS | WAIT_OVER | ALIVE | MOVE |
            | CONFIGURING | LOGIN | SPAWNING | - |
            | CONFIGURING | RESPAWN | CONFIGURING | - |
            | CONFIGURING | POSITION | CONFIGURING | - |
            | CONFIGURING | DIED | CONFIGURING | - |
            | CONFIGURING | WIN_GAME | CONFIGURING | - |
            | CONFIGURING | CONFIGURE | CONFIGURING | - |
            | CONFIGURING | DISCONNECT | DISCONNECTED | END_STEP |
            | CONFIGURING | TICK | CONFIGURING | - |
            | CONFIGURING | WAIT_OVER | CONFIGURING | - |
            | DISCONNECTED | LOGIN | DISCONNECTED | - |
            | DISCONNECTED | RESPAWN | DISCONNECTED | - |
            | DISCONNECTED | POSITION | DISCONNECTED | - |
            | DISCONNECTED | DIED | DISCONNECTED | - |
            | DISCONNECTED | WIN_GAME | DISCONNECTED | - |
            | DISCONNECTED | CONFIGURE | DISCONNECTED | - |
            | DISCONNECTED | DISCONNECT | DISCONNECTED | - |
            | DISCONNECTED | TICK | DISCONNECTED | - |
            | DISCONNECTED | WAIT_OVER | DISCONNECTED | - |
            """;

    @Test
    void theTableCoversEveryPair() {
        StringBuilder out = new StringBuilder("| state | input | next state | actions |\n|---|---|---|---|\n");
        for (Life.State s : Life.State.values()) {
            for (Life.Input i : Life.Input.values()) {
                Life.Transition t = Life.TABLE.get(s).get(i);
                assertThat(t.state()).isEqualTo(s);
                assertThat(t.input()).isEqualTo(i);
                out.append("| ").append(s).append(" | ").append(i).append(" | ").append(t.next()).append(" | ")
                        .append(t.actions().isEmpty() ? "-"
                                : String.join(", ", t.actions().stream().map(Enum::name).toList()))
                        .append(" |\n");
            }
        }
        assertThat(out.toString()).isEqualTo(EXPECTED);
    }

    @Test
    void aDeadPlayerAsksOnceAfterTheDeathScreenEvenWhenTheRuleChangesMeanwhile() {
        Life life = placed();
        life.on(Life.Input.DIED);
        assertThat(ticksUntil(life, Life.State.RESPAWN_ASKED)).isEqualTo(Life.RESPAWN_DELAY);
        life.on(Life.Input.RESPAWN);
        life.on(Life.Input.POSITION);
        life.on(Life.Input.DIED);
        tick(life, 5);
        // The server turns the death screen off after the first tick: the player asks on the next one, not never.
        life.immediateRespawn(true);
        assertThat(life.tick().has(Life.Action.ASK_RESPAWN)).isTrue();
        assertThat(ticksUntil(life, Life.State.ALIVE)).as("asks once, then waits for the Respawn").isEqualTo(-1);
    }

    @Test
    void theCreditsHoldMovesUntilARespawnAPositionOrTheWait() {
        Life life = placed();
        life.on(Life.Input.WIN_GAME);
        assertThat(ticksUntil(life, Life.State.ALIVE)).isEqualTo(Life.CREDITS_WAIT);
        life.on(Life.Input.WIN_GAME);
        tick(life, Life.CREDITS_WAIT - 1);
        assertThat(life.on(Life.Input.WIN_GAME).has(Life.Action.ASK_RESPAWN)).isTrue();
        assertThat(ticksUntil(life, Life.State.ALIVE)).as("a repeated WIN_GAME restarts the wait")
                .isEqualTo(Life.CREDITS_WAIT);
    }

    private static Life placed() {
        Life life = new Life();
        life.on(Life.Input.LOGIN);
        life.on(Life.Input.POSITION);
        return life;
    }

    private static void tick(Life life, int ticks) {
        for (int i = 0; i < ticks; i++) {
            life.tick();
        }
    }

    /** The tick on which the player reaches {@code state}, within 200; -1 when it does not. */
    private static int ticksUntil(Life life, Life.State state) {
        for (int i = 1; i <= 200; i++) {
            if (life.tick().next() == state) {
                return i;
            }
        }
        return -1;
    }
}
