package io.github.imnotstable.msc.bot;

import static io.github.imnotstable.msc.bot.Players.at;
import static io.github.imnotstable.msc.bot.Players.drive;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Movement steps run against {@link Physics} over a {@link FlatWorld}, tick by tick. */
class ScriptStepsTest {
    @Test
    void gotoWalksToItsTarget() {
        TestPlayer p = at(new FlatWorld(), null, 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("goto 6.5,1,0.5"), 200)).startsWith("reached in 1.");
        assertThat(p.body.x).isBetween(6.15, 6.85);
    }

    @Test
    void gotoReportsAWallAsBlocked() {
        FlatWorld w = new FlatWorld();
        for (int z = -6; z <= 6; z++) {
            w.set(3, 1, z, "minecraft:stone").set(3, 2, z, "minecraft:stone");
        }
        TestPlayer p = at(w, null, 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("goto 6.5,0.5"), 200)).startsWith("blocked: no progress for 2s, 3.");
    }

    @Test
    void gotoWithJumpCrossesAGapAndWithoutItFalls() {
        TestPlayer jumper = at(new FlatWorld().gap(4, 5), null, 0.5, 1, 0.5);
        assertThat(drive(jumper, Script.parse("goto 8.5,1,0.5 sprint jump"), 200)).startsWith("reached in")
                .endsWith(", 1 jumps");
        TestPlayer walker = at(new FlatWorld().gap(4, 5), null, 0.5, 1, 0.5);
        assertThat(drive(walker, Script.parse("goto 8.5,1,0.5"), 200)).startsWith("fell: landed at y=-8.00");
    }

    @Test
    void jumpRisesAndLands() {
        TestPlayer p = at(new FlatWorld(), null, 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("jump"), 100)).isEqualTo("rose 1.25, landed at y=1.00");
    }

    @Test
    void forwardMovesForItsTicks() {
        TestPlayer p = at(new FlatWorld(), null, 0.5, 1, 0.5);
        p.yaw = -90; // +x
        assertThat(drive(p, Script.parse("forward 20"), 100)).startsWith("moved 3.");
        assertThat(p.body.x).isGreaterThan(3.5);
    }

    @Test
    void walkFollowsTheRouteAndJumpsItsGap() {
        List<int[]> route = new ArrayList<>();
        for (int x = 0; x <= 3; x++) {
            route.add(new int[] {x, 1, 0, 'w'});
        }
        route.add(new int[] {6, 1, 0, 'j'});
        route.add(new int[] {7, 1, 0, 'w'});
        TestPlayer p = at(new FlatWorld().gap(4, 5), Players.route(route), 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("walk 7,1,0"), 200)).startsWith("reached in").endsWith(", 5 steps, 1 jump");
    }

    @Test
    void walkReportsWhereThePlayerFellOffTheRoute() {
        List<int[]> route = new ArrayList<>();
        for (int x = 0; x <= 7; x++) {
            route.add(new int[] {x, 1, 0, 'w'}); // walks straight over the gap: a wrong route
        }
        TestPlayer p = at(new FlatWorld().gap(4, 5), Players.route(route), 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("walk 7,1,0"), 200)).startsWith("fell: landed at ")
                .contains(",-8.0,0.5, off the route before step");
    }

    @Test
    void aLongGotoTimesOutWhileStillMakingProgress() {
        TestPlayer p = at(new FlatWorld(), null, 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("goto 1000.5,0.5"), 20 * 61)).startsWith("timed out after 60s, ");
    }

    @Test
    void aLongWalkTimesOutWhileStillMakingProgress() {
        List<int[]> route = new ArrayList<>();
        for (int x = 0; x <= 1000; x++) {
            route.add(new int[] {x, 1, 0, 'w'});
        }
        TestPlayer p = at(new FlatWorld(), Players.route(route), 0.5, 1, 0.5);
        assertThat(drive(p, Script.parse("walk 1000,1,0"), 20 * 61)).startsWith("timed out after 60s before step ");
    }

    @Test
    void aStepThatGivesNoResultStopsRunning() throws InterruptedException {
        TestPlayer p = at(new FlatWorld(), null, 0.5, 1, 0.5);
        Script.Step walk = Script.parse("goto 1000.5,0.5");
        p.begin(walk);
        assertThat(p.await(walk, java.time.Duration.ofMillis(10))).isEqualTo("stopped: no result (the server may have stopped)");
        assertThat(p.current()).isNull();
        double x = p.body.x;
        p.tick();
        p.tick();
        assertThat(p.body.x).isCloseTo(x, org.assertj.core.api.Assertions.within(0.2)); // only sliding to a stop
    }
}
