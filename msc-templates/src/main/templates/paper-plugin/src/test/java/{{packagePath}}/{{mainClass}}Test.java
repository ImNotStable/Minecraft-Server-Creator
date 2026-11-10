package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Loads the real plugin on a MockBukkit server: use this kind of test for events and commands. */
class {{mainClass}}Test {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.load({{mainClass}}.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Every message the player has received so far, as plain text. */
    private static List<String> messages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        for (Component c = player.nextComponentMessage(); c != null; c = player.nextComponentMessage()) {
            out.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }

    @Test
    void greetsPlayersWhenTheyJoin() {
        PlayerMock steve = server.addPlayer("Steve");
        assertThat(messages(steve)).contains("Welcome, Steve!");
    }

    @Test
    void helloCommandRepliesToTheSender() {
        PlayerMock alex = server.addPlayer("Alex");
        messages(alex); // drop the join greeting

        alex.performCommand("hello");

        assertThat(messages(alex)).containsExactly("Hello, Alex!");
    }
}
