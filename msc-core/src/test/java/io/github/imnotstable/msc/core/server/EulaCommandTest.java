package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EulaCommandTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    /** A scripted terminal: {@code interactive} false stands for piped input, as from a tool or script. */
    private static final class FakeTerminal implements EulaCommand.Terminal {
        final boolean interactive;
        final Deque<String> answers = new ArrayDeque<>();
        final List<String> out = new ArrayList<>();
        int prompts;

        FakeTerminal(boolean interactive, String... answers) {
            this.interactive = interactive;
            this.answers.addAll(List.of(answers));
        }

        @Override
        public boolean interactive() {
            return interactive;
        }

        @Override
        public String readLine(String prompt) {
            prompts++;
            out.add(prompt);
            return answers.poll();
        }

        @Override
        public void println(String line) {
            out.add(line);
        }

        String text() {
            return String.join("\n", out);
        }
    }

    private int run(FakeTerminal t, String... args) throws Exception {
        return EulaCommand.run(List.of(args), dir.resolve(".msc/eula.json"), dir, t, CLOCK, "owner", "pc");
    }

    @Test
    void refusesWithoutAnInteractiveTerminal() throws Exception {
        FakeTerminal piped = new FakeTerminal(false, "yes");
        assertThat(run(piped, "accept")).isEqualTo(2);
        assertThat(piped.prompts).isZero();
        assertThat(piped.text()).contains("Refused").contains("eula accept");
        assertThat(dir.resolve(".msc/eula.json")).doesNotExist();
    }

    @Test
    void writesOnlyOnAnExplicitYes() throws Exception {
        for (String answer : new String[] {"", "no", "y", "sure", null}) {
            FakeTerminal t = answer == null ? new FakeTerminal(true) : new FakeTerminal(true, answer);
            assertThat(run(t, "accept")).as(String.valueOf(answer)).isEqualTo(1);
            assertThat(t.text()).contains("https://aka.ms/MinecraftEULA").contains("nothing was written");
        }
        assertThat(dir.resolve(".msc/eula.json")).doesNotExist();

        FakeTerminal yes = new FakeTerminal(true, " YES ");
        assertThat(run(yes, "accept")).isZero();
        assertThat(yes.text()).contains("for user owner on pc").contains("Accepted.");
        assertThat(MachineEula.read(dir.resolve(".msc/eula.json")))
                .contains(new MachineEula.Acceptance("owner", "pc", CLOCK.instant()));

        FakeTerminal again = new FakeTerminal(true);
        assertThat(run(again, "accept")).isZero();
        assertThat(again.prompts).isZero();
        assertThat(again.text()).startsWith("Already accepted for this machine by owner on pc, 2026-11-12");
    }

    @Test
    void statusRevokeAndUsage() throws Exception {
        FakeTerminal t = new FakeTerminal(false);
        assertThat(run(t, "status")).isEqualTo(1);
        assertThat(run(t, "revoke")).isZero();
        assertThat(t.text()).contains("not accepted for this machine").contains("Nothing to revoke");
        MachineEulaAccess.accepted(dir.resolve(".msc/eula.json"));
        assertThat(run(t, "status")).isZero();
        assertThat(run(t, "revoke")).isZero();
        assertThat(dir.resolve(".msc/eula.json")).doesNotExist();
        assertThat(run(t)).isEqualTo(2);
        assertThat(run(t, "agree")).isEqualTo(2);
        assertThat(t.text()).contains("accepted for this machine by owner on pc").contains("Removed ")
                .contains("usage: eula accept | status | revoke");
    }
}
