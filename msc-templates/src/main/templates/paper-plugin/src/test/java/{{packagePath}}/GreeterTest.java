package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Plain unit test: no server needed. Prefer this kind of test for logic. */
class GreeterTest {
    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void fillsInThePlayerName() {
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);
        assertThat(plain(greeter.join("Steve"))).isEqualTo("Welcome, Steve!");
        assertThat(plain(greeter.hello("Alex"))).isEqualTo("Hello, Alex!");
    }

    @Test
    void playerNamesAreNotParsedAsTags() {
        Greeter greeter = new Greeter("Hi <player>", "unused");
        assertThat(plain(greeter.join("<red>Eve"))).isEqualTo("Hi <red>Eve");
    }
}
