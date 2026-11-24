package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #517: an argument the chosen way does not read is refused with what to do, not dropped with an OK. */
class TextureUnreadArgsTest {
    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace).withAgent();
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    private record Row(String tool, List<Object> args, String error) {}

    private static final List<Object> DRAW = List.of("name", "item/q", "palette", Drawings.palette("#d4263a"), "rows",
            Drawings.rows());
    private static final List<Object> COPY = List.of("name", "item/q", "copy", "item/ruby", "color", "#40c0ff");
    private static final List<Object> ANIMATE = List.of("name", "item/ruby", "animate", "pulse");
    private static final List<Object> PNG_COPY = List.of("name", "out/q.png", "copy", "item/ruby", "color", "#40c0ff");

    private static List<Object> plus(List<Object> base, Object... more) {
        List<Object> all = new java.util.ArrayList<>(base);
        all.addAll(List.of(more));
        return all;
    }

    private static Row texture(List<Object> args, String error) {
        return new Row("texture", args, "error: " + error);
    }

    @Test
    void anArgumentTheWayDoesNotReadIsRefused() {
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        String palette = "palette is for rows; copy takes color";
        List<Row> table = List.of(
                texture(plus(COPY, "palette", List.of("a=#000000")), palette),
                texture(plus(COPY, "palette", List.of()), palette),
                texture(plus(COPY, "palette", "one"), palette),
                texture(plus(PNG_COPY, "palette", List.of("a=#000000")), palette),
                texture(plus(DRAW, "color", "#ffffff"), "color is for copy; rows take their colors from palette"),
                texture(plus(ANIMATE, "color", "#ffffff"), "color is for copy; rows take their colors from palette"),
                texture(plus(COPY, "frames", 4), "frames is for animate; leave it out or add animate"),
                texture(plus(DRAW, "frametime", 4), "frametime is for animate; leave it out or add animate"),
                texture(plus(ANIMATE, "model", "item"),
                        "model is written only with rows or copy into the pack; leave it out"),
                texture(plus(PNG_COPY, "model", "item"),
                        "model is written only with rows or copy into the pack; leave it out"),
                texture(plus(PNG_COPY, "rows", List.of("rr")), "give one of rows or copy, not rows and copy"),
                new Row("pack", List.of("check", true, "file", "out/p.zip"),
                        "error: file is for a build; leave it out with check=true"),
                new Row("pack", List.of("check", true, "description", "x"),
                        "error: description is for a build; leave it out with check=true"),
                new Row("pack", List.of("check", true, "apply", false),
                        "error: apply is for a build; leave it out with check=true"),
                new Row("pack", List.of("check", true, "required", true),
                        "error: required is for a build; leave it out with check=true"));
        for (Row row : table) {
            assertThat(h.error(row.tool(), row.args().toArray())).as("%s %s", row.tool(), row.args())
                    .isEqualTo(row.error());
        }
        assertThat(workspace.resolve("out")).doesNotExist();
        // The ways still take what they read.
        h.ok("texture", plus(DRAW, "frames", 4, "animate", "pulse").toArray());
        h.ok("texture", plus(COPY, "model", "item").toArray());
        h.ok("texture", plus(ANIMATE, "frametime", 3).toArray());
        h.ok("pack", "check", true, "limit", 3);
    }
}
