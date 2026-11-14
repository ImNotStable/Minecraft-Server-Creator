package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.textures.Patterns;
import io.github.imnotstable.msc.textures.Shapes;
import io.github.imnotstable.msc.textures.Texture;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #523: every texture kind times every generate argument. A declared argument changes the texture for some value;
 * an undeclared one is refused in one line. A new kind or argument fails here until KindArgs declares it.
 */
class KindArgsTest {
    private static final Gson GSON = new Gson();

    /** Values to try per argument; the first one that changes the texture proves the argument is read. */
    private static final Map<String, List<Object>> VALUES = Map.of(
            "color", List.of("#40c0ff", "#ffffff"),
            "contrast", List.of(0.2, 1.5),
            "accent", List.of("#000000", "#ffffff", "#00ff00"),
            "size", List.of(32, 64),
            "seed", List.of(2, 99, 12345));

    private static Texture generate(String kind, String arg, Object value) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", kind);
        json.addProperty("color", "#d4263a");
        if (arg != null) {
            json.add(arg, GSON.toJsonTree(value));
        }
        return TextureTools.generated(new Args(json));
    }

    @Test
    void everyKindAndEveryArgumentIsDeclared() {
        assertThat(KindArgs.KINDS.stream().map(KindArgs::kind))
                .containsExactlyElementsOf(Stream.concat(Patterns.NAMES.stream(), Shapes.NAMES.stream()).toList());
        assertThat(WayArgs.TEXTURE.stream().filter(w -> w.readers().contains("kind")).map(WayArgs::arg))
                .as("WayArgs' kind arguments are KindArgs.ARGS").containsExactlyInAnyOrderElementsOf(KindArgs.ARGS);
        assertThat(VALUES.keySet()).as("test values for every argument")
                .containsExactlyInAnyOrderElementsOf(KindArgs.ARGS);
    }

    @Test
    void eachArgumentIsReadOrRefused() {
        for (KindArgs k : KindArgs.KINDS) {
            Texture base = generate(k.kind(), null, null);
            for (String arg : KindArgs.ARGS) {
                if (k.reads().contains(arg)) {
                    assertThat(VALUES.get(arg).stream().map(v -> generate(k.kind(), arg, v)))
                            .as("%s reads %s, so some value changes it", k.kind(), arg)
                            .anyMatch(t -> !t.equals(base));
                } else {
                    for (Object v : VALUES.get(arg)) {
                        assertThatThrownBy(() -> generate(k.kind(), arg, v)).as("%s %s", k.kind(), arg)
                                .isInstanceOf(ToolException.class)
                                .hasMessage(KindArgs.refusal(arg, k.kind()))
                                .hasMessageNotContaining("\n");
                    }
                }
            }
        }
        assertThat(KindArgs.refusal("seed", "gem"))
                .isEqualTo("seed is for kind noise, ore, planks and bricks; gem does not use it, leave it out");
    }
}
