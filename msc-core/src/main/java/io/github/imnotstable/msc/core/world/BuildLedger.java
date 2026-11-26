package io.github.imnotstable.msc.core.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The world's memory (ADR 0113): one entry per reviewed build in {@code .msc/builds.json}, written by the reviews an
 * agent runs anyway (id, name, box, when, review status, an optional line on why), plus the site plan written before
 * building. It outlives the session, so a later session asks what stands somewhere instead of re-reading the world.
 * Every answer is a line or two; the ledger never costs a call of its own.
 */
public final class BuildLedger {
    /** The file in a workspace's {@code .msc} folder. */
    public static final String FILE = "builds.json";
    /** Builds a lookup lists before it points at the full list. */
    static final int SHOWN = 5;
    /** Longest kept name, why line and plan, so the ledger stays cheap to read back. */
    static final int MAX_NAME = 40;
    static final int MAX_WHY = 160;
    static final int MAX_PLAN = 4000;
    /** Share of the smaller box two boxes must have in common to be one build reviewed again. */
    private static final double SAME_BUILD = 0.5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** A plan line naming a build: "smithy: north of the square, ...", optionally bulleted. */
    private static final Pattern PLAN_LINE = Pattern.compile("^\\s*(?:[-*]|\\d+[.)])?\\s*([^:\\n]{1,40}):(.*)$");
    /** A block id written in a plan line: two or more words joined by underscores. */
    private static final Pattern BLOCK_ID = Pattern.compile("(?<![\\w:])(?:minecraft:)?([a-z]+(?:_[a-z]+)+)\\b");

    /** One build. {@code status}: passed, failing: fixes, or edited since review. */
    record Entry(String id, String name, String world, String box, String made, String reviewed, String status,
                 String why) {
    }

    /** What the file holds. */
    record Data(String plan, List<Entry> builds) {
        Data {
            builds = builds == null ? new ArrayList<>() : new ArrayList<>(builds);
        }
    }

    private final Supplier<Path> file;
    private final Clock clock;

    /** {@code file} gives the ledger's path each time (the workspace can change); null keeps no ledger. */
    public BuildLedger(Supplier<Path> file) {
        this(file, Clock.systemUTC());
    }

    BuildLedger(Supplier<Path> file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** A ledger that keeps nothing: tests and tools without a workspace. */
    public static BuildLedger none() {
        return new BuildLedger(() -> null);
    }

    /**
     * Records a review of {@code region} (its text and the blocks it read) under {@code name} and {@code why}, both
     * optional, and returns the lines to add to the review: the build's id, and for a planned build its plan line and
     * the block ids that line names but the build lacks. A ledger that cannot be written says so and the review stands.
     */
    public synchronized String reviewed(Region region, String review, BlockGrid grid, String name, String why) {
        name = clean(name, MAX_NAME);
        why = clean(why, MAX_WHY);
        Path path = file.get();
        if (path == null) {
            return "";
        }
        Data data;
        try {
            data = read(path);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return "\nledger: not updated, " + path + " could not be read (" + e.getMessage() + ")";
        }
        List<String> fixes = OpenReviews.fixes(review);
        String status = fixes.isEmpty() ? "passed" : "failing: " + String.join(", ", fixes);
        String today = today();
        int at = match(data.builds(), region, name);
        Entry entry;
        if (at < 0) {
            entry = new Entry(nextId(data.builds()), name, region.world(), box(region), today, today, status, why);
            data.builds().add(entry);
        } else {
            Entry old = data.builds().get(at);
            entry = new Entry(old.id(), name == null || name.equalsIgnoreCase(old.name()) ? old.name() : name, region.world(), box(region), old.made(),
                    today, status, why != null ? why : old.why());
            data.builds().set(at, entry);
        }
        String failed = write(path, data);
        StringBuilder out = new StringBuilder("\nledger: ").append(label(entry));
        if (failed != null) {
            out.append(" (not saved: ").append(failed).append(')');
        } else if (entry.name() == null) {
            out.append(" (name= on the next review names it)");
        }
        String planned = planLine(data.plan(), entry.name());
        if (planned != null) {
            out.append("\nplan: ").append(planned);
            List<String> missing = missing(planned, grid);
            if (!missing.isEmpty()) {
                out.append("\nplan names ").append(String.join(", ", missing)).append("; none here");
            }
        }
        return out.toString();
    }

    /** A build edit in {@code box}: a passed build it touches is marked edited since its review. */
    public synchronized void edited(Region box) {
        edited(box, java.util.Set.of());
    }

    /** The same, leaving passed the builds reviewed as one of {@code same}: their blocks did not change (ADR 0119). */
    public synchronized void edited(Region box, java.util.Set<Region> same) {
        Path path = file.get();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            Data data = read(path);
            boolean changed = false;
            for (int i = 0; i < data.builds().size(); i++) {
                Entry e = data.builds().get(i);
                if ("passed".equals(e.status()) && overlap(region(e), box) > 0 && !same.contains(region(e))) {
                    data.builds().set(i, new Entry(e.id(), e.name(), e.world(), e.box(), e.made(), e.reviewed(),
                            "edited since review", e.why()));
                    changed = true;
                }
            }
            if (changed) {
                write(path, data);
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            // The edit stands; the next review of the build records its state again.
        }
    }

    /** The builds that overlap {@code region}, one line each, or empty: what stands here. */
    public synchronized String at(Region region) {
        Data data;
        try {
            data = readIfThere();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return "";
        }
        if (data == null) {
            return "";
        }
        List<Entry> here = data.builds().stream().filter(e -> overlap(region(e), region) > 0).toList();
        if (here.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("builds here:");
        here.stream().limit(SHOWN).forEach(e -> out.append("\n- ").append(line(e)));
        if (here.size() > SHOWN) {
            out.append("\n- ").append(here.size() - SHOWN).append(" more (msc_status builds=true)");
        }
        return out.toString();
    }

    /** Saves the site plan, replacing the last one. */
    public synchronized String plan(String text) {
        String plan = text == null ? "" : text.strip();
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("plan is empty: one line per build, \"name: place, size, palette, "
                    + "roof, what sets it apart\"");
        }
        if (plan.length() > MAX_PLAN) {
            throw new IllegalArgumentException("plan is " + plan.length() + " characters; keep it under " + MAX_PLAN
                    + ": one short line per build");
        }
        Path path = file.get();
        if (path == null) {
            return "no workspace, so no ledger to keep the plan";
        }
        Data data;
        try {
            data = readIfThere();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return "plan not saved: " + path + " could not be read (" + e.getMessage() + ")";
        }
        Data next = new Data(plan, data == null ? null : data.builds());
        String failed = write(path, next);
        if (failed != null) {
            return "plan not saved: " + failed;
        }
        List<String> names = planned(plan);
        return "plan saved in " + path + ": " + names.size() + (names.size() == 1 ? " build" : " builds") + " named"
                + (names.isEmpty() ? " (start each build's line with its name and a colon, \"smithy: ...\")" : " ("
                + String.join(", ", names) + "); review each with world_summary review=true name=<its name>");
    }

    /** The whole ledger: the plan, each build, and planned builds no review has named yet. */
    public synchronized String list() {
        Data data;
        try {
            data = readIfThere();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return "build ledger " + file.get() + " could not be read (" + e.getMessage() + ")";
        }
        if (data == null || (data.plan() == null && data.builds().isEmpty())) {
            return "build ledger is empty: msc_status plan= saves the site plan, and each world_summary review=true "
                    + "adds its build";
        }
        StringBuilder out = new StringBuilder();
        if (data.plan() != null) {
            out.append("plan:\n").append(data.plan()).append('\n');
        }
        out.append(data.builds().size()).append(data.builds().size() == 1 ? " build:" : " builds:");
        data.builds().forEach(e -> out.append("\n- ").append(line(e)).append(", ").append(Coords.show(region(e))));
        Set<String> named = new LinkedHashSet<>();
        data.builds().forEach(e -> {
            if (e.name() != null) {
                named.add(e.name().toLowerCase(Locale.ROOT));
            }
        });
        List<String> notYet = planned(data.plan()).stream().filter(n -> !named.contains(n.toLowerCase(Locale.ROOT)))
                .toList();
        if (!notYet.isEmpty()) {
            out.append("\nplanned, never reviewed: ").append(String.join(", ", notYet));
        }
        return out.toString();
    }

    /** The names a plan gives its builds, in its order. */
    static List<String> planned(String plan) {
        List<String> out = new ArrayList<>();
        if (plan == null) {
            return out;
        }
        for (String line : plan.split("\\R")) {
            Matcher m = PLAN_LINE.matcher(line);
            if (m.matches() && !m.group(1).isBlank()) {
                out.add(m.group(1).strip());
            }
        }
        return out;
    }

    /** The plan's line for {@code name} (after the colon), or null. */
    static String planLine(String plan, String name) {
        if (plan == null || name == null) {
            return null;
        }
        for (String line : plan.split("\\R")) {
            Matcher m = PLAN_LINE.matcher(line);
            if (m.matches() && m.group(1).strip().equalsIgnoreCase(name)) {
                return m.group(2).strip();
            }
        }
        return null;
    }

    /** The block ids a plan line names that the build holds none of. */
    static List<String> missing(String planLine, BlockGrid grid) {
        Set<String> here = new LinkedHashSet<>();
        for (String state : grid.palette()) {
            here.add(BlockNames.brief(BlockNames.id(state)));
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = BLOCK_ID.matcher(planLine.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (!here.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    private int match(List<Entry> builds, Region region, String name) {
        if (name != null) {
            for (int i = 0; i < builds.size(); i++) {
                if (name.equalsIgnoreCase(builds.get(i).name())) {
                    return i;
                }
            }
        }
        int best = -1;
        double most = 0;
        for (int i = 0; i < builds.size(); i++) {
            Entry e = builds.get(i);
            // A named build is only reviewed again under its own name, or a review of its exact box.
            if (name != null && e.name() != null && !box(region).equals(e.box())) {
                continue;
            }
            Region r = region(e);
            long smaller = Math.min(r.volume(), region.volume());
            double share = smaller == 0 ? 0 : (double) overlap(r, region) / smaller;
            if (share >= SAME_BUILD && share > most) {
                most = share;
                best = i;
            }
        }
        return best;
    }

    private static long overlap(Region a, Region b) {
        if (!a.world().equals(b.world())) {
            return 0;
        }
        long x = Math.min(a.max().x(), b.max().x()) - Math.max(a.min().x(), b.min().x()) + 1;
        long y = Math.min(a.max().y(), b.max().y()) - Math.max(a.min().y(), b.min().y()) + 1;
        long z = Math.min(a.max().z(), b.max().z()) - Math.max(a.min().z(), b.min().z()) + 1;
        return x <= 0 || y <= 0 || z <= 0 ? 0 : x * y * z;
    }

    private static Region region(Entry e) {
        return Coords.region(e.world(), e.box());
    }

    private static String box(Region r) {
        return Coords.show(r.min()) + " " + Coords.show(r.max());
    }

    private static String label(Entry e) {
        return e.id() + (e.name() == null ? "" : " " + e.name());
    }

    private static String line(Entry e) {
        return label(e) + " (" + e.status() + ", reviewed " + e.reviewed() + ")" + (e.why() == null ? "" : ": " + e.why());
    }

    private static String nextId(List<Entry> builds) {
        int max = 0;
        for (Entry e : builds) {
            try {
                max = Math.max(max, Integer.parseInt(e.id().substring(1)));
            } catch (RuntimeException ignored) {
                // A hand-edited id: new ids keep counting from the others.
            }
        }
        return "b" + (max + 1);
    }

    private static String clean(String text, int max) {
        if (text == null) {
            return null;
        }
        String one = text.replaceAll("\\s+", " ").strip();
        if (one.isEmpty()) {
            return null;
        }
        return one.length() <= max ? one : one.substring(0, max);
    }

    private String today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    private Data readIfThere() throws IOException {
        Path path = file.get();
        return path == null || !Files.isRegularFile(path) ? null : read(path);
    }

    private static Data read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new Data(null, null);
        }
        Data data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
        if (data == null) {
            return new Data(null, null);
        }
        for (Entry e : data.builds()) {
            if (e == null || e.id() == null || e.world() == null || e.box() == null) {
                throw new IllegalStateException("a build without id, world or box");
            }
            try {
                region(e);
            } catch (RuntimeException bad) {
                throw new IllegalStateException(e.id() + "'s box: " + bad.getMessage(), bad);
            }
        }
        return data;
    }

    /** Null when written, else why not. */
    private static String write(Path path, Data data) {
        try {
            Files.createDirectories(path.getParent());
            AtomicFile.write(path, to -> Files.writeString(to, GSON.toJson(data) + "\n", StandardCharsets.UTF_8));
            return null;
        } catch (IOException | RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }
}
