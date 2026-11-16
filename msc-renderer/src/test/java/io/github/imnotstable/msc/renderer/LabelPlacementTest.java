package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every label is whole, inside the image and on top; every image fits its size budget. */
class LabelPlacementTest {
    private static final List<String> FIXED_VIEWS = List.of("top", "north", "south", "east", "west", "iso", "iso-ne",
            "iso-nw", "iso-sw");
    private final WorldRenderer renderer = new WorldRenderer();

    /** What one render showed: marker dots drawn and how many of them had no room for their number. */
    private record Checked(int dots, int crowded) {
    }

    @Test
    void randomRendersKeepEveryLabelWholeAndEveryImageInsideItsBudget() {
        Random random = new Random(0x5eed_342L);
        int dots = 0;
        int crowded = 0;
        for (int run = 0; run < 240; run++) {
            int x0 = random.nextInt(4) == 0 ? random.nextInt(-30_000, 30_000) : random.nextInt(-200, 200);
            int y0 = random.nextInt(-64, 200);
            int z0 = random.nextInt(4) == 0 ? random.nextInt(-30_000, 30_000) : random.nextInt(-200, 200);
            int sx = 1 + random.nextInt(random.nextBoolean() ? 16 : 110);
            int sy = 1 + random.nextInt(random.nextBoolean() ? 4 : 40);
            int sz = 1 + random.nextInt(random.nextBoolean() ? 16 : 110);
            Scenes scene = new Scenes(x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1)
                    .fill(x0, y0, z0, x0 + sx - 1, y0, z0 + sz - 1, "stone");
            for (int i = random.nextInt(20); i > 0; i--) {
                scene.set(x0 + random.nextInt(sx), y0 + random.nextInt(sy), z0 + random.nextInt(sz), "oak_planks");
            }
            String view = view(random, x0, y0, z0, sx, sy, sz);
            int size = random.nextInt(3) == 0 ? random.nextInt(64, 1569) : random.nextInt(64, 520);
            RenderOptions options = new RenderOptions(size, false, random.nextBoolean(), random.nextInt(5) > 0, false);
            List<Marker> markers = markers(random, x0, y0, z0, sx, sy, sz);
            String what = String.format(Locale.ROOT, "run %d: %s at %d of %dx%dx%d with %d markers", run, view, size,
                    sx, sy, sz, markers.size());
            Checked checked = check(renderer.draw(scene.grid(), View.parse(view), options, markers), size, markers.size(), what);
            dots += checked.dots();
            crowded += checked.crowded();
        }
        assertThat(dots).isGreaterThan(500);
        // Only images with a side under 100 px (thin side views, tiny sizes) may run out of room for numbers.
        assertThat(crowded).as("dots left without a number").isLessThan(dots / 10);
    }

    @Test
    void aDotNeverCoversTheNumberOfTheDotBeforeIt() {
        // #342: cows 4 blocks apart at 7 px per block, and mobs in the east-most column and the south-east corner.
        Scenes scene = new Scenes(0, 0, 0, 63, 0, 63).fill(0, 0, 0, 63, 0, 63, "grass_block");
        List<Marker> markers = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            markers.add(Marker.entity("minecraft:cow", null, 10 + 4 * i, 0, 10));
        }
        markers.add(Marker.entity("minecraft:zombie", null, 63.7, 0, 30));
        markers.add(Marker.entity("minecraft:zombie", null, 63.7, 0, 63.7));
        WorldRenderer.Drawn drawn = renderer.draw(scene.grid(), View.parse("top"), RenderOptions.defaults(), markers);
        check(drawn, 512, markers.size(), "cow row");
        assertThat(drawn.result().caption()).doesNotContain("crowded", "Outside");
        assertThat(drawn.labels().stream().filter(l -> l.kind() == Labels.Kind.NUMBER).map(Labels.Label::text))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
    }

    @Test
    void rulerLabelsAtTheFarEndsStayWhole() {
        // #343: at 4 px per block the label of the last column, 100, used to lose its last digit.
        for (int min : new int[] {0, 1000}) {
            Scenes scene = new Scenes(min, 0, min, min + 100, 0, min + 100).fill(min, 0, min, min + 100, 0, min + 100,
                    "stone");
            WorldRenderer.Drawn drawn = renderer.draw(scene.grid(), View.parse("top"), RenderOptions.defaults(),
                    List.of());
            check(drawn, 512, 0, "grid from " + min);
            assertThat(drawn.labels().stream().filter(l -> l.kind() == Labels.Kind.RULER).map(Labels.Label::text))
                    .as("both rulers keep both ends").containsSubsequence(Integer.toString(min),
                            Integer.toString(min + 100), Integer.toString(min), Integer.toString(min + 100));
        }
    }

    private static String view(Random random, int x0, int y0, int z0, int sx, int sy, int sz) {
        int pick = random.nextInt(FIXED_VIEWS.size() + 2);
        if (pick < FIXED_VIEWS.size()) {
            return FIXED_VIEWS.get(pick);
        }
        double cx = x0 + sx / 2.0;
        double cz = z0 + sz / 2.0;
        double ex = cx + random.nextDouble(-1.5, 1.5) * (sx + 4);
        double ey = y0 + sy + random.nextDouble(1, 20);
        double ez = cz + random.nextDouble(-1.5, 1.5) * (sz + 4);
        String eye = String.format(Locale.ROOT, "eye %.2f,%.2f,%.2f", ex, ey, ez);
        return pick == FIXED_VIEWS.size()
                ? eye + String.format(Locale.ROOT, " look %.2f,%.2f,%.2f", cx, y0 + 0.5, cz)
                : eye + String.format(Locale.ROOT, " yaw %.1f pitch %.1f fov %d", random.nextDouble(-180, 180),
                        random.nextDouble(-10, 80), 30 + random.nextInt(100));
    }

    /** Scattered markers, rows a few blocks apart, and markers in the region's far column and corner. */
    private static List<Marker> markers(Random random, int x0, int y0, int z0, int sx, int sy, int sz) {
        List<Marker> markers = new ArrayList<>();
        int count = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(16);
        double rowZ = z0 + random.nextDouble(sz);
        double gap = 2 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            double x;
            double z;
            switch (random.nextInt(4)) {
                case 0 -> {
                    x = x0 + Math.min(sx - 0.01, 0.5 + i * gap);
                    z = rowZ;
                }
                case 1 -> {
                    x = x0 + sx - 0.3;
                    z = random.nextBoolean() ? z0 + sz - 0.3 : z0 + random.nextDouble(sz);
                }
                default -> {
                    x = x0 + random.nextDouble(sx);
                    z = z0 + random.nextDouble(sz);
                }
            }
            double y = y0 + 1 + random.nextInt(Math.max(1, sy));
            markers.add(switch (random.nextInt(8)) {
                case 0 -> Marker.entity("minecraft:zombie", null, x, y, z);
                case 1 -> Marker.entity("minecraft:player", "Alex", x, y, z);
                case 2 -> Marker.entity("minecraft:item", null, x, y, z);
                case 3 -> Marker.entity("myplugin:ghost", null, x, y, z);
                case 4 -> Marker.entity("minecraft:text_display", "Welcome\nto spawn", x, y, z);
                case 5 -> Marker.sign((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), List.of("Shop", "",
                        "Buy here", ""));
                default -> Marker.entity("minecraft:cow", null, x, y, z);
            });
        }
        return markers;
    }

    /**
     * Checks one render through its placement step: the image fits the budget; every label lies inside the image,
     * covers no other label and is drawn whole on top (its pixels in the image are exactly its own); numbers and text
     * boxes cover no dot; and every marker the caption lists has its number or its text box, unless the caption says
     * it is outside the image or too crowded.
     */
    private static Checked check(WorldRenderer.Drawn drawn, int maxSize, int markers, String what) {
        RenderResult result = drawn.result();
        BufferedImage image = Scenes.decode(result);
        assertThat(Math.max(image.getWidth(), image.getHeight())).as(what).isLessThanOrEqualTo(maxSize);
        assertThat(List.of(image.getWidth(), image.getHeight())).as(what)
                .containsExactly(result.width(), result.height());
        List<Labels.Label> labels = drawn.labels();
        for (int i = 0; i < labels.size(); i++) {
            Labels.Label label = labels.get(i);
            Labels.Rect box = label.box();
            assertThat(box.inside(image.getWidth(), image.getHeight())).as("%s: %s inside", what, label.text()).isTrue();
            for (Labels.Label other : labels.subList(i + 1, labels.size())) {
                assertThat(box.overlaps(other.box())).as("%s: %s under %s", what, label.text(), other.text()).isFalse();
            }
            if (label.kind() == Labels.Kind.NUMBER || label.kind() == Labels.Kind.TEXT_BOX) {
                assertThat(drawn.dots()).as("%s: dots under %s", what, label.text()).noneMatch(box::overlaps);
            }
            int[] alone = new int[box.w() * box.h()];
            Arrays.fill(alone, -1);
            label.at(0, 0).draw(alone, box.w(), box.h());
            for (int p = 0; p < alone.length; p++) {
                if (alone[p] != -1) {
                    int rgb = image.getRGB(box.x() + p % box.w(), box.y() + p / box.w()) & 0xffffff;
                    if (rgb != alone[p]) {
                        throw new AssertionError(what + ": label " + label.text() + " is not drawn whole");
                    }
                }
            }
        }
        String caption = result.caption();
        int listed = Math.min(markers, 40);
        Set<Integer> outside = numbers(caption, "Outside the image: ");
        Set<Integer> crowded = numbers(caption, "Dots too crowded for their number: ");
        int dots = 0;
        for (int n = 1; n <= listed; n++) {
            if (outside.contains(n)) {
                continue;
            }
            int number = n;
            List<Labels.Label> own = labels.stream().filter(l -> l.marker() == number).toList();
            if (own.isEmpty() || own.getFirst().kind() == Labels.Kind.NUMBER) {
                dots++;
            }
            if (crowded.contains(n)) {
                assertThat(own).as(what).isEmpty();
                continue;
            }
            assertThat(own).as("%s: marker %d has its number or text", what, n).hasSize(1);
            if (own.getFirst().kind() == Labels.Kind.NUMBER) {
                assertThat(own.getFirst().text()).isEqualTo(Integer.toString(n));
            }
        }
        if (Math.min(image.getWidth(), image.getHeight()) >= 100) {
            assertThat(crowded).as("%s: numbers left out of a roomy image", what).isEmpty();
        }
        return new Checked(dots, crowded.size());
    }

    private static Set<Integer> numbers(String caption, String lead) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher matcher = Pattern.compile(Pattern.quote(lead) + "([0-9, ]+)\\.").matcher(caption);
        if (matcher.find()) {
            for (String n : matcher.group(1).split(", ")) {
                numbers.add(Integer.parseInt(n));
            }
        }
        return numbers;
    }
}
