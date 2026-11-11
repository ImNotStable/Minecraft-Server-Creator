package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Plain unit test: no server needed. Prefer this kind of test for logic. */
class GreeterTest {
    @Test
    void greetsByName() {
        assertThat(PlainTextComponentSerializer.plainText().serialize(new Greeter().hello("Steve")))
                .isEqualTo("Hello, Steve!");
    }
}
