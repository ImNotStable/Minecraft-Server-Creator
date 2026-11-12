package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Plain unit test: no server needed. Prefer this kind of test for logic. */
class GreeterTest {
    @Test
    void fillsInThePlayerName() {
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);
        assertThat(greeter.join("Steve").getString()).isEqualTo("Welcome, Steve!");
        assertThat(greeter.hello("Alex").getString()).isEqualTo("Hello, Alex!");
    }

    @Test
    void playerNamesAreShownAsTyped() {
        assertThat(Greeter.fill("Hi {player}", "§cEve")).isEqualTo("Hi §cEve");
    }
}
