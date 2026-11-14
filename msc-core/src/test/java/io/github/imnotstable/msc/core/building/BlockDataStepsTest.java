package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Block names with block entity data in build steps (ADR 0042): spaces, commas and brackets inside the data. */
class BlockDataStepsTest {
    private static final String DATA = "{CustomName:'\"A, b]\"', Items:[{Slot:0b, id:\"minecraft:stone\"}]}";

    @Test
    void aStepKeepsSpacesAndCommasInsideTheDataAsOneBlock() {
        BuildScript.Parts p = BuildScript.split("set 1,2,3 chest[facing=east]" + DATA + " replace=air");
        assertThat(p.block()).isEqualTo("chest[facing=east]" + DATA);
        assertThat(p.options()).containsEntry("replace", "air");
        assertThat(BlockPattern.splitTopLevel("chest" + DATA + ":3,stone")).containsExactly("chest" + DATA + ":3", "stone");
        assertThat(BlockPattern.parse("chest" + DATA + ":3,stone").states().getFirst())
                .isEqualTo("minecraft:chest{CustomName:\"\\\"A, b]\\\"\",Items:[{Slot:0b,id:\"minecraft:stone\"}]}");
    }

    @Test
    void theStateTurnsAndTheDataStays() {
        assertThat(Turn.state("minecraft:chest[facing=north]{a:\"north\"}", 1)).isEqualTo("minecraft:chest[facing=east]{a:\"north\"}");
    }

    @Test
    void replaceMatchesABlockByItsStateWhateverItsData() {
        assertThat(Canvas.matches("minecraft:chest[facing=north]{Items:[{a:[1]}]}", List.of("minecraft:chest[facing=north]")))
                .isTrue();
        assertThat(Canvas.matches("minecraft:chest[facing=north]{a:\"[facing=south]\"}", List.of("minecraft:chest[facing=south]")))
                .isFalse();
    }
}
