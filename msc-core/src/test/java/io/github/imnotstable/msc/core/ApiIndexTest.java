package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.api.ApiIndex;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiIndexTest {
    @TempDir
    static Path dir;
    private static ApiIndex index;
    private static Path jar;

    /** Pack the compiled fixture classes into a jar, the way an API jar would arrive. */
    @BeforeAll
    static void buildJar() throws Exception {
        jar = dir.resolve("fixture-api.jar");
        String base = "io/github/imnotstable/msc/core/fixture/";
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            for (String name : List.of("Gadget.class", "Gadget$Mode.class", "Widget.class")) {
                zip.putNextEntry(new JarEntry(base + name));
                try (InputStream in = ApiIndexTest.class.getClassLoader().getResourceAsStream(base + name)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        index = ApiIndex.load(List.of(jar));
    }

    @Test
    void describesAClassWithSignaturesAndParameterNames() {
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.describe("Gadget", null, false, 40)).isEqualTo("""
                class io.github.imnotstable.msc.core.fixture.Gadget : Widget
                static final int MAX_SIZE
                String label
                Gadget(String label)
                String label()
                Map<String, List<? extends T>> sortInto(List<T> items, int[] order)
                void oldWay() (deprecated)
                static Gadget create(String label, Gadget.Mode mode)""");
    }

    @Test
    void filtersInheritsAndListsEnumConstants() {
        assertThat(index.describe("Gadget", "labelled", true, 40))
                .endsWith("default boolean isLabelled()  <- Widget");
        assertThat(index.describe("Gadget", "labelled", false, 40)).contains("try inherited=true");
        assertThat(index.describe("Gadget.Mode", null, false, 40))
                .isEqualTo("enum io.github.imnotstable.msc.core.fixture.Gadget.Mode\nconstants: FAST SAFE");
        assertThat(index.describe("Gadget", null, false, 2)).endsWith("[5 more members; use filter or raise limit]");
        assertThatThrownBy(() -> index.find("Gizmo")).isInstanceOf(ToolException.class).hasMessageContaining("api_search");
    }

    @Test
    void searchRanksClassesFirstAndFindsMembersAndConstants() {
        assertThat(index.search("gadget", null, 2)).startsWith("class io.github.imnotstable.msc.core.fixture.Gadget\n");
        assertThat(index.search("gadget create", null, 5))
                .isEqualTo("Gadget#static Gadget create(String label, Gadget.Mode mode)");
        assertThat(index.search("safe", null, 5)).isEqualTo("Gadget.Mode.SAFE");
        assertThat(index.search("label", "interface", 5)).isEqualTo("Widget#String label()\nWidget#default boolean isLabelled()");
        assertThat(index.search("nothing here", null, 5)).isEqualTo("no matches for \"nothing here\"");
    }

    @Test
    void toolsUseTheConfiguredJars(@TempDir Path workspace) throws Exception {
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withApiJars(List.of(jar)))) {
            assertThat(h.ok("api_class", "name", "Widget")).startsWith("interface io.github.imnotstable.msc.core.fixture.Widget");
            assertThat(h.ok("api_search", "query", "sortInto")).contains("sortInto(List<T> items, int[] order)");
            assertThat(h.error("api_search", "query", "x", "kind", "blob")).contains("kind must be");
            assertThat(h.ok("msc_status")).contains("api index: 3 classes").contains("agent: not connected");
        }
    }

    @Test
    void statusNamesOnlyTheFirstJarsOfALongList(@TempDir Path workspace) throws Exception {
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withApiJars(List.of(jar, jar, jar, jar, jar)))) {
            h.ok("api_search", "query", "gadget");
            assertThat(h.ok("msc_status")).contains("and 2 more jars");
        }
    }
}
