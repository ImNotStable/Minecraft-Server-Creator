package io.github.imnotstable.msc.stdlib.cmd;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommandTreeTest {
    /** A sender that records what it was told. */
    static final class Sender implements CommandTree.Source {
        final UUID id;
        final Set<String> nodes;
        final List<String> said = new ArrayList<>();

        Sender(UUID id, Set<String> nodes) {
            this.id = id;
            this.nodes = nodes;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public String name() {
            return id == null ? "console" : "Alex";
        }

        @Override
        public boolean permitted(String node) {
            return nodes.contains(node);
        }

        @Override
        public void reply(String text) {
            said.add(text);
        }

        @Override
        public void fail(String text) {
            said.add("!" + text);
        }
    }

    @Test
    void routesSubcommandsChecksPermissionsCooldownsAndCompletes() {
        List<String> ran = new ArrayList<>();
        CommandTree party = CommandTree.create("party").description("Parties")
                .sub(CommandTree.create("invite").aliases("inv").usage("<player>").permission("party.invite")
                        .tab(c -> List.of("Alex", "Sam")).executes(c -> ran.add("invite " + c.arg(0))))
                .sub(CommandTree.create("leave").playerOnly().cooldown(Duration.ofMinutes(1))
                        .executes(c -> ran.add("leave " + c.player())))
                .sub(CommandTree.create("say").executes(c -> ran.add("say " + c.rest(0))));
        UUID id = UUID.randomUUID();
        Sender alex = new Sender(id, Set.of("party.invite"));
        Sender nobody = new Sender(UUID.randomUUID(), Set.of());
        Sender console = new Sender(null, Set.of("party.invite"));

        assertThat(party.dispatch(alex, "party", new String[]{"inv", "Sam"})).isTrue();
        assertThat(party.dispatch(nobody, "party", new String[]{"invite", "Sam"})).isFalse();
        assertThat(nobody.said).containsExactly("!You don't have permission to do that.");
        assertThat(party.dispatch(console, "party", new String[]{"leave"})).isFalse();
        assertThat(party.dispatch(alex, "party", new String[]{"leave"})).isTrue();
        assertThat(party.dispatch(alex, "party", new String[]{"leave"})).isFalse();
        assertThat(alex.said.getLast()).startsWith("!Wait ");
        party.dispatch(alex, "party", new String[]{"say", "hello", "there"});
        party.dispatch(alex, "party", new String[]{"invite"});
        assertThat(alex.said.getLast()).isEqualTo("!Missing argument 1.");
        assertThat(ran).containsExactly("invite Sam", "leave " + id, "say hello there");

        // No executor at the root: the help, listing only allowed subcommands.
        party.dispatch(nobody, "party", new String[0]);
        assertThat(nobody.said.getLast()).contains("/party leave").contains("/party say").doesNotContain("invite");

        assertThat(party.complete(alex, new String[]{""})).containsExactly("invite", "leave", "say");
        assertThat(party.complete(nobody, new String[]{"in"})).isEmpty();
        assertThat(party.complete(alex, new String[]{"invite", "S"})).containsExactly("Sam");
    }
}
