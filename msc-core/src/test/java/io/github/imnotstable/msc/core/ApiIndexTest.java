package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.api.ApiIndex;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        assertThat(index.describe("Gadget", null, false, 40, 300)).isEqualTo("""
                class io.github.imnotstable.msc.core.fixture.Gadget : Widget
                static final int MAX_SIZE
                String label
                Gadget(String label)
                String label()
                Map<String, List<? extends T>> sortInto(List<T> items, int[] order)
                void oldWay() (deprecated)
                static Gadget create(String label, Gadget.Mode mode)
                static final int MAX_WIDTH  <- Widget""");
    }

    @Test
    void filtersInheritsAndListsEnumConstants() {
        assertThat(index.describe("Gadget", "labelled", true, 40, 300))
                .endsWith("default boolean isLabelled()  <- Widget");
        assertThat(index.describe("Gadget", "labelled", false, 40, 300)).contains("try inherited=true");
        assertThat(index.describe("Gadget.Mode", null, false, 40, 300))
                .isEqualTo("enum io.github.imnotstable.msc.core.fixture.Gadget.Mode\nconstants: FAST SAFE");
        assertThat(index.describe("Gadget", null, false, 2, 300)).endsWith("[6 more members; use filter or raise limit (at most 300)]");
        // At the most a caller may ask for, the hint names only what still helps.
        assertThat(index.describe("Gadget", null, false, 2, 2)).endsWith("[6 more members; use filter to narrow the list]");
        assertThatThrownBy(() -> index.find("Gizmo")).isInstanceOf(ToolException.class).hasMessageContaining("api_search");
    }

    @Test
    void showsConstantsInheritedFromInterfacesWithoutInherited() {
        assertThat(index.describe("Gadget", "width", false, 40, 300))
                .isEqualTo("class io.github.imnotstable.msc.core.fixture.Gadget : Widget\nstatic final int MAX_WIDTH  <- Widget");
        assertThat(index.describe("Gadget", "width", true, 40, 300)).containsOnlyOnce("MAX_WIDTH");
    }

    @Test
    void searchRanksClassesFirstAndFindsMembersAndConstants() {
        assertThat(index.search("gadget", null, 2, 100)).startsWith("class io.github.imnotstable.msc.core.fixture.Gadget\n");
        assertThat(index.search("gadget create", null, 5, 100))
                .isEqualTo("Gadget#static Gadget create(String label, Gadget.Mode mode)");
        assertThat(index.search("safe", null, 5, 100)).isEqualTo("Gadget.Mode.SAFE");
        assertThat(index.search("label", "interface", 5, 100)).isEqualTo("Widget#String label()\nWidget#default boolean isLabelled()");
        assertThat(index.search("nothing here", null, 5, 100)).isEqualTo("no matches for \"nothing here\"");
    }

    @Test
    void eventKindFindsTheEventsOfEveryPlatform() throws Exception {
        Path events = dir.resolve("events.jar");
        try (OutputStream out = Files.newOutputStream(events); JarOutputStream zip = new JarOutputStream(out)) {
            synthetic(zip, "a.BukkitJoinEvent", "org.bukkit.event.Event", null);
            synthetic(zip, "b.NeoJoinEvent", "net.neoforged.bus.api.Event", null);
            synthetic(zip, "c.ForgeJoinEvent", "java.lang.Record", "net.minecraftforge.eventbus.api.event.RecordEvent");
            synthetic(zip, "c.ForgeJoinMutable", "net.minecraftforge.eventbus.api.event.MutableEvent", null);
            synthetic(zip, "c.ForgeJoinInheritable", "java.lang.Object", "net.minecraftforge.eventbus.api.event.InheritableEvent");
            synthetic(zip, "d.MinestomJoinEvent", "java.lang.Object", "net.minestom.server.event.Event");
            synthetic(zip, "net.fabricmc.fabric.api.networking.v1.JoinEvents", "java.lang.Object", null);
            synthetic(zip, "net.fabricmc.fabric.api.event.player.JoinCallback", "java.lang.Object", null);
            synthetic(zip, "x.JoinHelper", "java.lang.Object", null);
        }
        String found = ApiIndex.load(List.of(events)).search("join", "event", 20, 100);
        assertThat(found.lines().map(l -> l.substring(l.lastIndexOf(' ') + 1))).containsExactlyInAnyOrder(
                "a.BukkitJoinEvent", "b.NeoJoinEvent", "c.ForgeJoinEvent", "c.ForgeJoinMutable",
                "c.ForgeJoinInheritable", "d.MinestomJoinEvent", "net.fabricmc.fabric.api.networking.v1.JoinEvents",
                "net.fabricmc.fabric.api.event.player.JoinCallback");
    }

    /** One agent family's API as {@link ApiIndex#findJars} finds it in the Gradle cache, with one real-shaped event. */
    private record Family(String jar, String event, String superclass, String iface) {
    }

    /**
     * #606: every platform's {@code api_search kind=event} finds its events in the jars {@link ApiIndex#findJars}
     * picks, and not a look-alike that is no event. A platform with a new agent family needs a row here.
     */
    private static final Map<String, Family> FAMILIES = Map.of(
            "paper", new Family("io.papermc.paper/paper-api/1/a/paper-api-1.jar",
                    "org.bukkit.event.player.PlayerJoinEvent", "org.bukkit.event.Event", null),
            "fabric", new Family("net.fabricmc.fabric-api/fabric-networking-api-v1/1/a/fabric-networking-api-v1-1.jar",
                    "net.fabricmc.fabric.api.networking.v1.ServerJoinEvents", "java.lang.Object", null),
            "neoforge", new Family("net.neoforged/neoforge/1/a/neoforge-1.jar",
                    "net.neoforged.neoforge.event.entity.player.PlayerJoinEvent", "net.neoforged.bus.api.Event", null),
            "forge", new Family("net.minecraftforge/forge/1/a/forge-1.jar", "net.minecraftforge.event.PlayerJoinEvent",
                    "java.lang.Record", "net.minecraftforge.eventbus.api.event.RecordEvent"),
            "minestom", new Family("net.minestom/minestom/1/a/minestom-1.jar",
                    "net.minestom.server.event.player.PlayerJoinEvent", "java.lang.Object", "net.minestom.server.event.Event"),
            // Velocity's events share no supertype: PostLoginEvent is a plain final class.
            "velocity", new Family("com.velocitypowered/velocity-api/1/a/velocity-api-1.jar",
                    "com.velocitypowered.api.event.connection.PostJoinEvent", "java.lang.Object", null));

    @Test
    void eachPlatformsEventSearchFindsItsEvents(@TempDir Path gradleHome) throws Exception {
        for (Map.Entry<String, Family> e : FAMILIES.entrySet()) {
            Family f = e.getValue();
            Path jar = gradleHome.resolve("caches/modules-2/files-2.1").resolve(f.jar());
            Files.createDirectories(jar.getParent());
            String pkg = f.event().substring(0, f.event().lastIndexOf('.'));
            try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
                synthetic(zip, f.event(), f.superclass(), f.iface());
                synthetic(zip, pkg + ".JoinHelper", "java.lang.Object", null);
                // Named like an event but an interface, as Velocity's ResultedEvent is.
                syntheticInterface(zip, pkg + ".JoinResultedEvent");
            }
        }
        for (Platform platform : Platform.values()) {
            Family f = FAMILIES.get(platform.agent());
            assertThat(f).as("no event fixture for %s's agent family %s", platform, platform.agent()).isNotNull();
            String found = ApiIndex.load(ApiIndex.findJars(gradleHome, platform.agent())).search("join", "event", 20, 100);
            assertThat(found).as(platform.id()).matches("(class|record) " + java.util.regex.Pattern.quote(f.event()));
        }
    }

    /** ADR 0059: on Paper, the server paperweight-userdev patched is indexed too, so NMS classes can be looked up. */
    @Test
    void paperAlsoIndexesTheNewestPaperweightServer(@TempDir Path gradleHome) throws Exception {
        Path work = gradleHome.resolve("caches/paperweight-userdev/v2/work");
        Path old = work.resolve("applyDevBundlePatches_old/output.jar");
        Path server = work.resolve("applyDevBundlePatches_new/output.jar");
        for (Path jar : List.of(old, server)) {
            Files.createDirectories(jar.getParent());
            try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
                synthetic(zip, "net.minecraft.server.level.ServerPlayer", "java.lang.Object", null);
            }
        }
        Files.createDirectories(work.resolve("applyPaperclipPatch_x"));
        old.toFile().setLastModified(1_000);

        assertThat(ApiIndex.findJars(gradleHome, "paper")).containsExactly(server);
        assertThat(ApiIndex.load(ApiIndex.findJars(gradleHome, "paper")).find("ServerPlayer").name())
                .isEqualTo("net.minecraft.server.level.ServerPlayer");
    }

    private static void syntheticInterface(JarOutputStream zip, String name) throws Exception {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(name), cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
                .withSuperclass(ClassDesc.of("java.lang.Object")));
        zip.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void synthetic(JarOutputStream zip, String name, String superclass, String iface) throws Exception {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(name), cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ClassDesc.of(superclass));
            if (iface != null) {
                cb.withInterfaceSymbols(ClassDesc.of(iface));
            }
        });
        zip.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
        zip.write(bytes);
        zip.closeEntry();
    }

    @Test
    void toolsUseTheConfiguredJars(@TempDir Path workspace) throws Exception {
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withApiJars(List.of(jar)))) {
            assertThat(h.ok("api_class", "name", "Widget")).startsWith("interface io.github.imnotstable.msc.core.fixture.Widget");
            assertThat(h.ok("api_search", "query", "sortInto")).contains("sortInto(List<T> items, int[] order)");
            assertThat(h.error("api_search", "query", "x", "kind", "blob")).contains("kind must be");
            // A limit past the most each tool lists is refused with the range, not clamped silently (#459).
            assertThat(h.error("api_search", "query", "x", "limit", 101))
                    .isEqualTo("error: argument limit must be 1 to 100, not 101");
            assertThat(h.error("api_class", "name", "Widget", "limit", 301))
                    .isEqualTo("error: argument limit must be 1 to 300, not 301");
            assertThat(h.error("api_class", "name", "Widget", "limit", 0)).contains("must be 1 to 300");
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
