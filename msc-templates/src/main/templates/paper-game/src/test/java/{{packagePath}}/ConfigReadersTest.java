package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The typed number readers in {@link GameConfig} at the edges of what they can be declared with: the widest and
 * narrowest ranges, one-sided ranges ({@code Double.POSITIVE_INFINITY} for no upper limit) and the bounds that are
 * refused when declared. Plain JUnit: no server is needed.
 */
class ConfigReadersTest {
    private static final double MAX = Double.MAX_VALUE;
    private static final double TINY = Double.MIN_VALUE;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final BigInteger HUGE = BigInteger.TEN.pow(30);
    private static final BigInteger TOO_HUGE = BigInteger.TEN.pow(400);

    /** A reader as declared, how its errors state what it needs, and values it must take and refuse. */
    private record Reader(String declared, Supplier<GameConfig.Need<?>> need, String what, List<Object> takes,
                          List<Object> refuses) {
    }

    /** Values every number reader refuses, whatever its range. */
    private static final List<Object> NEVER = List.of(Double.NaN, INF, -INF, "1", true, List.of(1));

    private static final List<Reader> READERS = List.of(
            new Reader("WHOLE_NUMBER", () -> GameConfig.WHOLE_NUMBER, "a whole number",
                    List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0L, (long) Integer.MAX_VALUE),
                    List.of((long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE, HUGE, 1.0, 0.5)),
            new Reader("wholeNumber(0, 0)", () -> GameConfig.wholeNumber(0, 0), "a whole number from 0 to 0",
                    List.of(0, 0L), List.of(-1, 1, 0.0)),
            new Reader("wholeNumber(MIN_VALUE, 0)", () -> GameConfig.wholeNumber(Integer.MIN_VALUE, 0),
                    "a whole number from -2147483648 to 0", List.of(Integer.MIN_VALUE, 0), List.of(1, Long.MIN_VALUE)),
            new Reader("wholeNumber(0, MAX_VALUE)", () -> GameConfig.wholeNumber(0, Integer.MAX_VALUE),
                    "a whole number from 0 to 2147483647", List.of(0, Integer.MAX_VALUE),
                    List.of(-1, (long) Integer.MAX_VALUE + 1)),
            new Reader("NUMBER", () -> GameConfig.NUMBER, "a number",
                    List.of(-MAX, MAX, 0, -0.0, TINY, Integer.MIN_VALUE, Long.MAX_VALUE, HUGE), List.of(TOO_HUGE)),
            new Reader("number(-inf, inf)", () -> GameConfig.number(-INF, INF), "a number",
                    List.of(-MAX, MAX, 0.5), List.of()),
            new Reader("number(0, inf)", () -> GameConfig.number(0, INF), "a number from 0 (no upper limit)",
                    List.of(0, -0.0, TINY, MAX, Long.MAX_VALUE, HUGE), List.of(-TINY, -1, -MAX, TOO_HUGE)),
            new Reader("number(-inf, 0)", () -> GameConfig.number(-INF, 0), "a number up to 0 (no lower limit)",
                    List.of(0, -0.0, -TINY, -MAX, Long.MIN_VALUE), List.of(TINY, 1, MAX)),
            new Reader("number(-2.5, inf)", () -> GameConfig.number(-2.5, INF), "a number from -2.5 (no upper limit)",
                    List.of(-2.5, -2, 3, MAX), List.of(-3, -2.5000001)),
            new Reader("number(-inf, 1e20)", () -> GameConfig.number(-INF, 1e20),
                    "a number up to 100000000000000000000 (no lower limit)", List.of(1e20, -MAX, Long.MAX_VALUE),
                    List.of(Math.nextUp(1e20), HUGE)),
            new Reader("number(-0.0, 0.0)", () -> GameConfig.number(-0.0, 0.0), "a number from 0 to 0",
                    List.of(0, -0.0, 0.0, 0L), List.of(TINY, -TINY)),
            new Reader("number(0.5, 0.5)", () -> GameConfig.number(0.5, 0.5), "a number from 0.5 to 0.5",
                    List.of(0.5), List.of(0, 1, Math.nextUp(0.5))),
            new Reader("number(0.1, 0.3)", () -> GameConfig.number(0.1, 0.3), "a number from 0.1 to 0.3",
                    List.of(0.1, 0.2, 0.3), List.of(0.1 + 0.2, 0, 1)),
            new Reader("number(-MAX_VALUE, MAX_VALUE)", () -> GameConfig.number(-MAX, MAX),
                    "a number from -" + BigDecimal.valueOf(MAX).toPlainString() + " to " + BigDecimal.valueOf(MAX).toPlainString(),
                    List.of(-MAX, MAX, 0), List.of()),
            new Reader("number(MIN_VALUE, 1)", () -> GameConfig.number(TINY, 1),
                    "a number from " + BigDecimal.valueOf(TINY).toPlainString() + " to 1", List.of(TINY, 1), List.of(0)));

    @TestFactory
    Stream<DynamicTest> eachReaderStatesItsRangeAndKeepsToIt() {
        return READERS.stream().map(r -> DynamicTest.dynamicTest(r.declared(), () -> {
            GameConfig.Need<?> need = r.need().get();
            assertThat(need.what()).isEqualTo(r.what());
            for (Object v : r.takes()) {
                assertThat(need.get(v, "key")).as("%s takes %s", r.declared(), v).isNotNull();
            }
            for (Object v : Stream.concat(r.refuses().stream(), NEVER.stream()).toList()) {
                assertThatThrownBy(() -> need.get(v, "key")).as("%s refuses %s", r.declared(), v)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith("key: needs " + r.what() + ", got ");
            }
        }));
    }

    /** The error a one-sided setting gives, as the plugin logs it. */
    @Test
    void aOneSidedRangeSaysWhichSideHasNoLimit() {
        assertThatThrownBy(() -> GameConfig.number(0, INF).get(-1, "speed"))
                .hasMessage("speed: needs a number from 0 (no upper limit), got the whole number -1");
        assertThatThrownBy(() -> GameConfig.number(-INF, 5).get(5.5, "depth"))
                .hasMessage("depth: needs a number up to 5 (no lower limit), got the decimal 5.5");
        assertThatThrownBy(() -> GameConfig.NUMBER.get(INF, "gravity"))
                .hasMessage("gravity: needs a number, got infinity (.inf)");
    }

    /** A bound that cannot work fails where it is declared, so the plugin does not start with it. */
    @Test
    void impossibleBoundsAreRefusedWhenDeclared() {
        double nan = Double.NaN;
        assertThatThrownBy(() -> GameConfig.number(nan, 1)).hasMessageStartingWith("number(NaN, 1.0): a bound is NaN;");
        assertThatThrownBy(() -> GameConfig.number(0, nan)).hasMessageContaining("Double.POSITIVE_INFINITY");
        assertThatThrownBy(() -> GameConfig.number(nan, nan)).hasMessageContaining("a bound is NaN");
        assertThatThrownBy(() -> GameConfig.number(1, 0)).hasMessage("number(1.0, 0.0): min is above max");
        assertThatThrownBy(() -> GameConfig.number(INF, -INF)).hasMessageEndingWith("min is above max");
        assertThatThrownBy(() -> GameConfig.number(INF, INF))
                .hasMessage("number(Infinity, Infinity): no finite number is in this range");
        assertThatThrownBy(() -> GameConfig.number(-INF, -INF)).hasMessageEndingWith("no finite number is in this range");
        assertThatThrownBy(() -> GameConfig.wholeNumber(5, 1)).hasMessage("wholeNumber(5, 1): min is above max");
        assertThatThrownBy(() -> GameConfig.wholeNumber(Integer.MAX_VALUE, Integer.MIN_VALUE))
                .hasMessageEndingWith("min is above max");
    }

    @Test
    void plainNumbersNeverThrow() {
        assertThat(GameConfig.plainNumber(5)).isEqualTo("5");
        assertThat(GameConfig.plainNumber(-0.0)).isEqualTo("0");
        assertThat(GameConfig.plainNumber(0.0001)).isEqualTo("0.0001");
        assertThat(GameConfig.plainNumber(1e20)).isEqualTo("100000000000000000000");
        assertThat(GameConfig.plainNumber(INF)).isEqualTo("infinity");
        assertThat(GameConfig.plainNumber(-INF)).isEqualTo("minus infinity");
        assertThat(GameConfig.plainNumber(Double.NaN)).isEqualTo("NaN");
    }
}
